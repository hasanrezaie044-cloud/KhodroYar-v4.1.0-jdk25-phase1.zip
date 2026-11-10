'use strict';

/** In-memory sliding counters. Fine for a single instance; see docs for multi-instance notes. */
class RateLimiter {
  constructor({ perMinute, perDay, now = () => Date.now() }) {
    this.perMinute = perMinute;
    this.perDay = perDay;
    this.now = now;
    this.hits = new Map(); // key -> number[] (timestamps within last 24h)
  }

  /** @returns {{ok:true}|{ok:false, retryAfterSec:number, scope:'minute'|'day'}} */
  take(key) {
    const t = this.now();
    const dayAgo = t - 86_400_000;
    const minAgo = t - 60_000;
    const list = (this.hits.get(key) || []).filter((x) => x > dayAgo);
    const inMinute = list.filter((x) => x > minAgo);
    if (inMinute.length >= this.perMinute) {
      const retry = Math.ceil((inMinute[0] + 60_000 - t) / 1000);
      this.hits.set(key, list);
      return { ok: false, retryAfterSec: Math.max(1, retry), scope: 'minute' };
    }
    if (list.length >= this.perDay) {
      const retry = Math.ceil((list[0] + 86_400_000 - t) / 1000);
      this.hits.set(key, list);
      return { ok: false, retryAfterSec: Math.max(1, retry), scope: 'day' };
    }
    list.push(t);
    this.hits.set(key, list);
    return { ok: true };
  }

  sweep() {
    const dayAgo = this.now() - 86_400_000;
    for (const [k, v] of this.hits) {
      const kept = v.filter((x) => x > dayAgo);
      if (kept.length) this.hits.set(k, kept);
      else this.hits.delete(k);
    }
  }
}

/** Global daily budget (UTC day). Stores counts only, never message content. */
class UsageBudget {
  constructor({ requestBudget, tokenBudget, now = () => Date.now() }) {
    this.requestBudget = requestBudget;
    this.tokenBudget = tokenBudget;
    this.now = now;
    this.reset();
  }

  day() {
    return new Date(this.now()).toISOString().slice(0, 10);
  }

  reset() {
    this.date = this.day();
    this.requests = 0;
    this.promptTokens = 0;
    this.completionTokens = 0;
    this.errors = 0;
  }

  roll() {
    if (this.date !== this.day()) this.reset();
  }

  exceeded() {
    this.roll();
    if (this.requestBudget > 0 && this.requests >= this.requestBudget) return 'requests';
    if (this.tokenBudget > 0 && this.promptTokens + this.completionTokens >= this.tokenBudget) return 'tokens';
    return null;
  }

  recordRequest() {
    this.roll();
    this.requests += 1;
  }

  recordUsage(usage) {
    this.roll();
    if (!usage) return;
    this.promptTokens += Number(usage.prompt_tokens) || 0;
    this.completionTokens += Number(usage.completion_tokens) || 0;
  }

  recordError() {
    this.roll();
    this.errors += 1;
  }

  snapshot() {
    this.roll();
    return {
      date: this.date,
      requests: this.requests,
      errors: this.errors,
      promptTokens: this.promptTokens,
      completionTokens: this.completionTokens,
      requestBudget: this.requestBudget,
      tokenBudget: this.tokenBudget,
    };
  }
}

module.exports = { RateLimiter, UsageBudget };
