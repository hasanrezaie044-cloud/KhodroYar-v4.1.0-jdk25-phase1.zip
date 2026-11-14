'use strict';

const { normalizeProviderBaseUrl } = require('./urls');

function int(name, def, { min = 0, max = Number.MAX_SAFE_INTEGER } = {}) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return def;
  const n = Number.parseInt(raw, 10);
  if (!Number.isFinite(n)) throw new Error(`Invalid ${name}: must be an integer`);
  return Math.min(max, Math.max(min, n));
}

function str(name, def = '') {
  const v = process.env[name];
  return v === undefined ? def : v.trim();
}

function loadConfig() {
  const baseUrl = normalizeProviderBaseUrl(str('GAPGPT_BASE_URL', 'https://api.gapgpt.app/v1'));
  const defaultModel = str('DEFAULT_MODEL', 'gpt-4o-mini');
  const allowed = str('ALLOWED_MODELS')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (!allowed.includes(defaultModel)) allowed.unshift(defaultModel);
  return {
    apiKey: str('GAPGPT_API_KEY'),
    baseUrl,
    defaultModel,
    allowedModels: allowed,
    port: int('PORT', 8787, { min: 1, max: 65535 }),
    host: str('HOST', '0.0.0.0'),
    trustProxy: str('TRUST_PROXY', '0') === '1',
    enabled: str('AI_ENABLED', '1') !== '0',
    clientToken: str('CLIENT_TOKEN'),
    adminToken: str('ADMIN_TOKEN'),
    timeoutMs: int('REQUEST_TIMEOUT_MS', 30000, { min: 1000, max: 120000 }),
  };
}

module.exports = { loadConfig };
