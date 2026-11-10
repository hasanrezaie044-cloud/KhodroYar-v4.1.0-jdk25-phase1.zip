#!/usr/bin/env node
'use strict';
/**
 * Real end-to-end connection test against a RUNNING backend (local or deployed).
 *   node scripts/test-connection.js https://your-backend.example [CLIENT_TOKEN]
 * It checks: /health, /v1/status, /v1/models (is the model really on your account?), and one
 * tiny real chat request (this spends a few tokens of your GapGPT credit).
 * It never prints keys.
 */
const base = (process.argv[2] || process.env.BACKEND_URL || 'http://127.0.0.1:8787').replace(/\/+$/, '');
const token = process.argv[3] || process.env.CLIENT_TOKEN || '';
const headers = { 'content-type': 'application/json', 'x-client-id': 'connection-test', ...(token ? { authorization: `Bearer ${token}` } : {}) };

async function step(name, fn) {
  try {
    const r = await fn();
    console.log(`PASS  ${name}${r ? ' - ' + r : ''}`);
    return true;
  } catch (e) {
    console.log(`FAIL  ${name} - ${e.message}`);
    return false;
  }
}
async function json(path, init) {
  const res = await fetch(base + path, { headers, ...init });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`HTTP ${res.status} ${body.error ? body.error.code + ': ' + body.error.message : ''}`);
  return body;
}

(async () => {
  let ok = true;
  let status;
  ok = (await step('health', async () => {
    const h = await json('/health');
    if (!h.configured) throw new Error('server is up but GAPGPT_API_KEY is NOT configured');
    return `enabled=${h.enabled}`;
  })) && ok;
  ok = (await step('status', async () => { status = await json('/v1/status'); return `defaultModel=${status.defaultModel}`; })) && ok;
  ok = (await step('model exists on your account', async () => {
    const m = await json('/v1/models');
    if (!m.defaultModelAvailable) throw new Error(`model "${m.defaultModel}" was NOT found in your GapGPT model list`);
    return m.defaultModel;
  })) && ok;
  ok = (await step('real chat request', async () => {
    const r = await json('/v1/chat', { method: 'POST', body: JSON.stringify({ messages: [{ role: 'user', content: 'فقط بنویس: سلام' }], max_tokens: 20 }) });
    return `reply="${String(r.content).slice(0, 40)}" tokens=${r.usage ? r.usage.promptTokens + '+' + r.usage.completionTokens : 'n/a'}`;
  })) && ok;
  console.log(ok ? '\nALL OK' : '\nSOME CHECKS FAILED');
  process.exit(ok ? 0 : 1);
})();
