'use strict';

/** Payload-free accounting (UTC day), not a request/token allowance. */
class UsageAccounting {
  constructor({ now = () => Date.now() } = {}) {
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
    };
  }
}

module.exports = { UsageAccounting };
