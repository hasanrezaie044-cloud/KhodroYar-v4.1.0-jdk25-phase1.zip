'use strict';

/** Normalise only explicit provider URLs; never probe guessed hosts or forward a key on redirects. */
function normalizeProviderBaseUrl(input) {
  let url;
  try { url = new URL(input); } catch { throw new Error('Invalid GAPGPT_BASE_URL'); }
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) {
    throw new Error('GAPGPT_BASE_URL must use https:// (http only allowed for localhost tests)');
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('GAPGPT_BASE_URL must not contain credentials, query parameters or a fragment');
  }
  let path = url.pathname.replace(/\/+$/, '');
  // Pasting the full Chat Completions/models URL used to append the endpoint twice -> 404.
  path = path.replace(/\/(?:chat\/completions|models)$/, '');
  if (!path) path = '/v1';
  return `${url.origin}${path}`;
}

module.exports = { normalizeProviderBaseUrl };
