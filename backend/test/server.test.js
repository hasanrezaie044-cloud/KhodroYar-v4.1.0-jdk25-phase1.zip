'use strict';
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const { createApp } = require('../src/app');
const { loadConfig } = require('../src/config');

const SECRET = 'sk-test-SECRET-KEY-123';

function startMock(handler) {
  return new Promise((resolve) => {
    const calls = [];
    const s = http.createServer((req, res) => {
      let b = '';
      req.on('data', (c) => (b += c));
      req.on('end', () => {
        calls.push({ url: req.url, auth: req.headers.authorization, body: b ? JSON.parse(b) : null });
        handler(req, res, calls.length);
      });
    });
    s.listen(0, '127.0.0.1', () => resolve({ s, calls, port: s.address().port }));
  });
}

async function boot(env, mockHandler) {
  const mock = await startMock(mockHandler);
  Object.assign(process.env, {
    GAPGPT_API_KEY: SECRET,
    GAPGPT_BASE_URL: `http://127.0.0.1:${mock.port}/v1`,
    DEFAULT_MODEL: 'gpt-4o-mini',
    MAX_RETRIES: '1',
    REQUEST_TIMEOUT_MS: '1500',
    RATE_LIMIT_PER_MINUTE: '5',
    RATE_LIMIT_PER_DAY: '50',
    DAILY_REQUEST_BUDGET: '100',
    DAILY_TOKEN_BUDGET: '0',
    AI_ENABLED: '1',
    CLIENT_TOKEN: '',
    ADMIN_TOKEN: 'adm',
    MAX_MESSAGE_CHARS: '200',
    MAX_TOTAL_CHARS: '1000',
    ...env,
  });
  const logs = [];
  const cfg = loadConfig();
  const { server } = createApp(cfg, { log: (l) => logs.push(JSON.stringify(l)) });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const close = () => new Promise((r) => server.close(() => mock.s.close(r)));
  return { base, mock, logs, close };
}

const ok = (req, res) => {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ model: 'gpt-4o-mini', choices: [{ message: { content: 'سلام' }, finish_reason: 'stop' }], usage: { prompt_tokens: 7, completion_tokens: 3 } }));
};
const post = (base, body, headers = {}) =>
  fetch(`${base}/v1/chat`, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) });
const msg = { messages: [{ role: 'user', content: 'سلام' }] };

test('chat happy path forwards key upstream only, never leaks it', async () => {
  const t = await boot({}, ok);
  const r = await post(t.base, msg);
  const text = await r.text();
  assert.equal(r.status, 200);
  assert.equal(JSON.parse(text).content, 'سلام');
  assert.equal(t.mock.calls[0].auth, `Bearer ${SECRET}`);
  assert.equal(t.mock.calls[0].body.max_tokens, 800);
  assert.ok(!text.includes(SECRET));
  assert.ok(!t.logs.join('').includes(SECRET));
  assert.ok(!t.logs.join('').includes('سلام'));
  await t.close();
});

test('health never exposes key', async () => {
  const t = await boot({}, ok);
  const txt = await (await fetch(`${t.base}/health`)).text();
  assert.ok(!txt.includes(SECRET));
  assert.equal(JSON.parse(txt).configured, true);
  await t.close();
});

test('validation: 400 for bad payloads, long message, bad model', async () => {
  const t = await boot({}, ok);
  assert.equal((await post(t.base, { messages: [] })).status, 400);
  assert.equal((await post(t.base, { messages: [{ role: 'user', content: 'x'.repeat(201) }] })).status, 400);
  assert.equal((await post(t.base, { ...msg, model: 'evil-model' })).status, 400);
  assert.equal((await post(t.base, { messages: [{ role: 'tool', content: 'x' }] })).status, 400);
  const r = await fetch(`${t.base}/v1/chat`, { method: 'POST', body: '{bad' });
  assert.equal(r.status, 400);
  await t.close();
});

test('max_tokens is capped to server limit', async () => {
  const t = await boot({ MAX_OUTPUT_TOKENS: '100' }, ok);
  await post(t.base, { ...msg, max_tokens: 99999 });
  assert.equal(t.mock.calls[0].body.max_tokens, 100);
  await t.close();
});

for (const [up, expected, code] of [[401, 502, 'provider_auth_failed'], [402, 402, 'credit_exhausted'], [429, 429, 'provider_rate_limited'], [500, 500, 'provider_error'], [400, 400, 'upstream_bad_request']]) {
  test(`upstream ${up} -> ${expected} ${code}`, async () => {
    const t = await boot({}, (q, res) => { res.writeHead(up, { 'retry-after': '7' }); res.end(`{"error":"${SECRET}"}`); });
    const r = await post(t.base, msg);
    const txt = await r.text();
    assert.equal(r.status, expected);
    assert.equal(JSON.parse(txt).error.code, code);
    assert.ok(!txt.includes(SECRET), 'upstream body must not be echoed');
    await t.close();
  });
}

test('retry on 503 then succeed; no retry on 4xx', async () => {
  const t = await boot({}, (q, res, n) => (n === 1 ? (res.writeHead(503), res.end('x')) : ok(q, res)));
  assert.equal((await post(t.base, msg)).status, 200);
  assert.equal(t.mock.calls.length, 2);
  await t.close();
  const t2 = await boot({}, (q, res) => { res.writeHead(400); res.end('x'); });
  await post(t2.base, msg);
  assert.equal(t2.mock.calls.length, 1);
  await t2.close();
});

test('timeout -> 504', async () => {
  const t = await boot({ REQUEST_TIMEOUT_MS: '1000', MAX_RETRIES: '0' }, () => {});
  const r = await post(t.base, msg);
  assert.equal(r.status, 504);
  t.mock.s.closeAllConnections?.();
  await t.close();
});

test('rate limit -> 429 with Retry-After', async () => {
  const t = await boot({ RATE_LIMIT_PER_MINUTE: '2' }, ok);
  await post(t.base, msg); await post(t.base, msg);
  const r = await post(t.base, msg);
  assert.equal(r.status, 429);
  assert.ok(Number(r.headers.get('retry-after')) >= 1);
  await t.close();
});

test('kill switch -> 503; missing key -> 503', async () => {
  let t = await boot({ AI_ENABLED: '0' }, ok);
  assert.equal((await post(t.base, msg)).status, 503);
  await t.close();
  t = await boot({ GAPGPT_API_KEY: '' }, ok);
  assert.equal((await post(t.base, msg)).status, 503);
  await t.close();
});

test('client token enforced', async () => {
  const t = await boot({ CLIENT_TOKEN: 'app-token' }, ok);
  assert.equal((await post(t.base, msg)).status, 401);
  assert.equal((await post(t.base, msg, { authorization: 'Bearer wrong' })).status, 401);
  assert.equal((await post(t.base, msg, { authorization: 'Bearer app-token' })).status, 200);
  await t.close();
});

test('daily request budget -> 402 budget_exceeded; admin usage has counts only', async () => {
  const t = await boot({ DAILY_REQUEST_BUDGET: '1' }, ok);
  assert.equal((await post(t.base, msg)).status, 200);
  const r = await post(t.base, msg);
  assert.equal(r.status, 402);
  assert.equal((await r.json()).error.code, 'budget_exceeded');
  const u = await fetch(`${t.base}/admin/usage`, { headers: { authorization: 'Bearer adm' } });
  const j = await u.json();
  assert.equal(j.requests, 1);
  assert.equal(j.promptTokens, 7);
  assert.equal((await fetch(`${t.base}/admin/usage`)).status, 401);
  await t.close();
});

test('models endpoint reports availability of default model', async () => {
  const t = await boot({}, (q, res) => { res.writeHead(200); res.end(JSON.stringify({ data: [{ id: 'gpt-4o-mini' }, { id: 'other' }] })); });
  const j = await (await fetch(`${t.base}/v1/models`)).json();
  assert.equal(j.defaultModelAvailable, true);
  assert.deepEqual(j.models, ['gpt-4o-mini']);
  await t.close();
});

test('unknown route 404', async () => {
  const t = await boot({}, ok);
  assert.equal((await fetch(`${t.base}/nope`)).status, 404);
  await t.close();
});
