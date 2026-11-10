'use strict';

const ROLES = new Set(['system', 'user', 'assistant']);

class HttpError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

/** Validates and normalises the client payload. Throws HttpError(400) on anything off. */
function validateChat(body, cfg) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new HttpError(400, 'invalid_request', 'Body must be a JSON object');
  }
  const { messages } = body;
  if (!Array.isArray(messages) || messages.length === 0) {
    throw new HttpError(400, 'invalid_request', '"messages" must be a non-empty array');
  }
  if (messages.length > cfg.maxMessages) {
    throw new HttpError(400, 'too_many_messages', `At most ${cfg.maxMessages} messages are allowed`);
  }
  let total = 0;
  const clean = messages.map((m, i) => {
    if (!m || typeof m !== 'object') throw new HttpError(400, 'invalid_request', `messages[${i}] is invalid`);
    if (!ROLES.has(m.role)) throw new HttpError(400, 'invalid_request', `messages[${i}].role is invalid`);
    if (typeof m.content !== 'string' || m.content.trim() === '') {
      throw new HttpError(400, 'invalid_request', `messages[${i}].content must be a non-empty string`);
    }
    // A system message (the app's tool prompt) may be larger than a user message.
    const limit = m.role === 'system' ? cfg.maxTotalChars : cfg.maxMessageChars;
    if (m.content.length > limit) {
      throw new HttpError(400, 'message_too_long', `messages[${i}] exceeds ${limit} characters`);
    }
    total += m.content.length;
    return { role: m.role, content: m.content };
  });
  if (total > cfg.maxTotalChars) {
    throw new HttpError(400, 'conversation_too_long', `Conversation exceeds ${cfg.maxTotalChars} characters`);
  }
  if (clean[clean.length - 1].role === 'assistant') {
    throw new HttpError(400, 'invalid_request', 'Last message must be from user or system');
  }

  let model = cfg.defaultModel;
  if (body.model !== undefined) {
    if (typeof body.model !== 'string' || !cfg.allowedModels.includes(body.model)) {
      throw new HttpError(400, 'model_not_allowed', 'Requested model is not allowed on this server');
    }
    model = body.model;
  }

  let maxTokens = cfg.maxOutputTokens;
  if (body.max_tokens !== undefined) {
    const n = Number(body.max_tokens);
    if (!Number.isInteger(n) || n < 1) throw new HttpError(400, 'invalid_request', '"max_tokens" must be a positive integer');
    maxTokens = Math.min(n, cfg.maxOutputTokens);
  }

  let temperature = 0.3;
  if (body.temperature !== undefined) {
    const t = Number(body.temperature);
    if (!Number.isFinite(t) || t < 0 || t > 1.5) throw new HttpError(400, 'invalid_request', '"temperature" must be between 0 and 1.5');
    temperature = t;
  }

  return { messages: clean, model, maxTokens, temperature, json: body.json === true };
}

module.exports = { validateChat, HttpError };
