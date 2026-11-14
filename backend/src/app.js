'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const { UsageAccounting } = require('./limits');
const { validateChat, HttpError } = require('./validate');
const { GapGptProvider } = require('./provider');

const MAX_BODY_BYTES = 32 * 1024 * 1024; // hostile-body/heap safety, never a token or usage allowance.

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

function send(res, status, obj, headers = {}) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end(body);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new HttpError(413, 'payload_too_large', 'Request body is too large'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || 'null'));
      } catch {
        reject(new HttpError(400, 'invalid_json', 'Body is not valid JSON'));
      }
    });
    req.on('error', () => reject(new HttpError(400, 'invalid_request', 'Could not read request')));
  });
}

/**
 * Builds the HTTP server. `deps` lets tests inject a fake provider/clock.
 * Logs contain only: method, path, status, latency, token counts. Never message text, never keys.
 */
function createApp(cfg, deps = {}) {
  const provider = deps.provider || new GapGptProvider(cfg);
  const budget = deps.budget || new UsageAccounting();
  const log = deps.log || ((line) => console.log(JSON.stringify(line)));

  function requireClient(req) {
    if (!cfg.clientToken) return;
    const h = String(req.headers.authorization || '');
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    if (!token || !safeEqual(token, cfg.clientToken)) {
      throw new HttpError(401, 'unauthorized', 'Missing or invalid client token');
    }
  }

  function guard(req) {
    if (!cfg.enabled) throw new HttpError(503, 'assistant_disabled', 'The assistant is currently disabled by the administrator');
    requireClient(req);

  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://x');
    const route = `${req.method} ${url.pathname}`;

    if (route === 'GET /health') {
      return send(res, 200, { ok: true, service: 'khodroyar-ai-backend', configured: Boolean(cfg.apiKey), enabled: cfg.enabled });
    }

    if (route === 'GET /v1/status') {
      requireClient(req);
      return send(res, 200, {
        enabled: cfg.enabled,
        configured: Boolean(cfg.apiKey),
        defaultModel: cfg.defaultModel,
        allowedModels: cfg.allowedModels,
        requestBodyMaxBytes: MAX_BODY_BYTES,
      });
    }

    if (route === 'GET /v1/models') {
      guard(req);
      if (!cfg.apiKey) throw new HttpError(503, 'not_configured', 'GAPGPT_API_KEY is not set on the server');
      const all = await provider.listModels();
      return send(res, 200, {
        models: all.filter((id) => cfg.allowedModels.includes(id)),
        defaultModel: cfg.defaultModel,
        defaultModelAvailable: all.includes(cfg.defaultModel),
      });
    }

    if (route === 'POST /v1/chat') {
      guard(req);
      if (!cfg.apiKey) throw new HttpError(503, 'not_configured', 'GAPGPT_API_KEY is not set on the server');
      const input = validateChat(await readJson(req), cfg);
      budget.recordRequest();
      const out = await provider.chat(input);
      budget.recordUsage(out.usage);
      res.usage = out.usage;
      return send(res, 200, {
        content: out.content,
        model: out.model,
        finishReason: out.finishReason,
        usage: out.usage
          ? { promptTokens: out.usage.prompt_tokens ?? null, completionTokens: out.usage.completion_tokens ?? null }
          : null,
      });
    }

    if (route === 'GET /admin/usage') {
      const h = String(req.headers.authorization || '');
      const token = h.startsWith('Bearer ') ? h.slice(7) : '';
      if (!cfg.adminToken || !token || !safeEqual(token, cfg.adminToken)) {
        throw new HttpError(401, 'unauthorized', 'Missing or invalid admin token');
      }
      return send(res, 200, budget.snapshot());
    }

    throw new HttpError(404, 'not_found', 'Unknown endpoint');
  }

  const server = http.createServer(async (req, res) => {
    const started = Date.now();
    let status = 500;
    try {
      await handle(req, res);
      status = res.statusCode;
    } catch (e) {
      const err = e instanceof HttpError ? e : new HttpError(500, 'internal_error', 'Internal server error');
      status = err.status;
      if (status >= 500) budget.recordError();
      const headers = err.extra && err.extra.retryAfterSec ? { 'Retry-After': String(err.extra.retryAfterSec) } : {};
      if (!res.headersSent) {
        send(res, err.status, { error: { code: err.code, message: err.message, ...(err.extra.retryAfterSec ? { retryAfterSec: err.extra.retryAfterSec } : {}) } }, headers);
      }
    }
    const u = res.usage;
    log({
      t: new Date().toISOString(),
      m: req.method,
      p: req.url.split('?')[0],
      s: status,
      ms: Date.now() - started,
      ...(u ? { pt: u.prompt_tokens, ct: u.completion_tokens } : {}),
    });
  });
  server.requestTimeout = cfg.timeoutMs + 5000;
  server.headersTimeout = 15000;
  return { server, budget };

}

module.exports = { createApp };
