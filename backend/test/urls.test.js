'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { normalizeProviderBaseUrl } = require('../src/urls');
const { GapGptProvider } = require('../src/provider');

test('provider root, version prefix and full completion URLs resolve to exactly one endpoint', () => {
  for (const input of [
    'https://api.example.com', 'https://api.example.com/', 'https://api.example.com/v1/',
    'https://api.example.com/v1/chat/completions', 'https://api.example.com/v1/chat/completions/',
    'https://api.example.com/v1/models',
  ]) assert.equal(normalizeProviderBaseUrl(input), 'https://api.example.com/v1', input);
  assert.equal(normalizeProviderBaseUrl('https://example.com/proxy/openai/v1/chat/completions'), 'https://example.com/proxy/openai/v1');
  assert.equal(normalizeProviderBaseUrl('http://127.0.0.1:1234/v1'), 'http://127.0.0.1:1234/v1');
});

test('unsafe provider URLs, credentials, query strings and fake localhost names are rejected', () => {
  for (const input of [
    'invalid', 'http://api.example.com/v1', 'http://localhost.evil.test/v1',
    'https://user:password@example.com/v1', 'https://example.com/v1?key=secret', 'https://example.com/v1#secret',
  ]) assert.throws(() => normalizeProviderBaseUrl(input), undefined, input);
});

test('404 does not falsely assert that only the model is missing', () => {
  const error = GapGptProvider.mapUpstreamStatus({ status: 404 });
  assert.equal(error.status, 502);
  assert.equal(error.code, 'upstream_not_found');
  assert.match(error.message, /endpoint or model/);
});

test('provider request uses the normalised URL and forbids redirects with the server key', async () => {
  const calls = [];
  const provider = new GapGptProvider({
    baseUrl: normalizeProviderBaseUrl('https://api.example.com/v1/chat/completions'),
    apiKey: 'test-only', maxRetries: 0, timeoutMs: 1000,
  }, async (url, options) => {
    calls.push({ url, options });
    return { ok: true, status: 200, json: async () => ({ choices: [{ message: { content: 'ok' } }] }) };
  });
  await provider.chat({ messages: [{ role: 'user', content: 'hello' }], model: 'test', maxTokens: 10, temperature: 0.2, json: true });
  assert.equal(calls[0].url, 'https://api.example.com/v1/chat/completions');
  assert.equal(calls[0].options.redirect, 'error');
});
