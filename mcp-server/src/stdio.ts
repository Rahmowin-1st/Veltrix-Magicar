import { serveStdio } from '@modelcontextprotocol/server/stdio';

import { stdioBindingFromEnv } from './auth.js';
import { backendFromEnv } from './backend.js';
import { createUltronMcpServer } from './server.js';

const backend = backendFromEnv();
const binding = stdioBindingFromEnv();

const handle = serveStdio(() => createUltronMcpServer(backend, binding));
console.error(`[ultron-mcp] stdio ready for ${binding.principalId}`);

process.on('SIGINT', () => {
  void handle.close();
});
process.on('SIGTERM', () => {
  void handle.close();
});
