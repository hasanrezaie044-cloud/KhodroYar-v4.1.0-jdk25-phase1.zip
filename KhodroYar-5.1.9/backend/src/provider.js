'use strict';

const { HttpError } = require('./validate');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * OpenAI-compatible provider (GapGPT). The API key is only ever placed in the outgoing
 * Authorization header and is never logged, returned, or included in error text.
 */
class GapGptProvider {
  constructor(cfg, fetchImpl = globalThis.fetch) {
    this.cfg = cfg;
    this.fetch = fetchImpl;
  }

  async #request(path, init) {
    const attempts = this.cfg.maxRetries + 1;
    let lastErr;
    for (let i = 0; i < attempts; i++) {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), this.cfg.timeoutMs);
      try {
        const res = await this.fetch(`${this.cfg.baseUrl}${path}`, {
          ...init,
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${this.cfg.apiKey}`,
            ...(init.headers || {}),
          },
          signal: ctrl.signal,
        });
        clearTimeout(timer);
        if ([502, 503, 504].includes(res.status) && i < attempts - 1) {
          await sleep(400 * (i + 1));
          continue;
        }
        return res;
      } catch (e) {
        clearTimeout(timer);
        lastErr = e;
        if (i < attempts - 1) {
          await sleep(400 * (i + 1));
          continue;
        }
      }
    }
    if (lastErr && lastErr.name === 'AbortError') {
      throw new HttpError(504, 'upstream_timeout', 'The AI provider did not respond in time');
    }
    throw new HttpError(502, 'upstream_unreachable', 'Could not reach the AI provider');
  }

  static mapUpstreamStatus(res) {
    const s = res.status;
    if (s === 400) return new HttpError(400, 'upstream_bad_request', 'The AI provider rejected the request');
    if (s === 401 || s === 403) {
      // Do not expose details; the operator must check the server key.
      return new HttpError(502, 'provider_auth_failed', 'Server is not authorised with the AI provider (administrator must check the API key)');
    }
    if (s === 402) return new HttpError(402, 'credit_exhausted', 'AI provider credit is exhausted');
    if (s === 404) return new HttpError(502, 'model_unavailable', 'The model is not available on this account');
    if (s === 429) {
      const ra = Number(res.headers.get('retry-after'));
      return new HttpError(429, 'provider_rate_limited', 'The AI provider is rate limiting requests', {
        retryAfterSec: Number.isFinite(ra) && ra > 0 ? ra : 20,
      });
    }
    return new HttpError(500, 'provider_error', 'The AI provider returned an error');
  }

  async chat({ messages, model, maxTokens, temperature, json }) {
    const payload = { model, messages, max_tokens: maxTokens, temperature, stream: false };
    if (json) payload.response_format = { type: 'json_object' };
    let res = await this.#request('/chat/completions', { method: 'POST', body: JSON.stringify(payload) });
    if (res.status === 400 && json) {
      // Some models/providers do not support response_format; retry once without it.
      delete payload.response_format;
      res = await this.#request('/chat/completions', { method: 'POST', body: JSON.stringify(payload) });
    }
    if (!res.ok) throw GapGptProvider.mapUpstreamStatus(res);
    let data;
    try {
      data = await res.json();
    } catch {
      throw new HttpError(500, 'provider_error', 'The AI provider returned an unreadable response');
    }
    const choice = data && data.choices && data.choices[0];
    const content = choice && choice.message && choice.message.content;
    if (typeof content !== 'string') throw new HttpError(500, 'provider_error', 'The AI provider returned an empty response');
    return { content, model: data.model || model, usage: data.usage || null, finishReason: choice.finish_reason || null };
  }

  async listModels() {
    const res = await this.#request('/models', { method: 'GET' });
    if (!res.ok) throw GapGptProvider.mapUpstreamStatus(res);
    const data = await res.json();
    const list = Array.isArray(data.data) ? data.data : [];
    return list.map((m) => m && m.id).filter((x) => typeof x === 'string');
  }
}

module.exports = { GapGptProvider };
