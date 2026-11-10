'use strict';

const { loadConfig } = require('./config');
const { createApp } = require('./app');

try {
  require('node:fs').accessSync('.env');
  if (typeof process.loadEnvFile === 'function') process.loadEnvFile('.env');
} catch {
  /* no .env file: rely on real environment variables */
}

const cfg = loadConfig();
if (!cfg.apiKey) {
  console.warn('[warn] GAPGPT_API_KEY is not set. /health works, but /v1/chat will answer 503 until it is configured.');
}
const { server } = createApp(cfg);
server.listen(cfg.port, cfg.host, () => {
  console.log(`KhodroYar AI backend listening on ${cfg.host}:${cfg.port} (model: ${cfg.defaultModel}, enabled: ${cfg.enabled})`);
});
for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => server.close(() => process.exit(0)));
