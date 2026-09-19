import assert from 'node:assert/strict';

import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { createMcpHandler } from '@modelcontextprotocol/server';

import { MemoryUltronBackend } from './backend.js';
import type { MainAssistantOrchestrator } from './main-assistant-orchestrator.js';
import { createUltronMcpServer } from './server.js';
import { SubmissionRateLimiter } from './submission-rate-limit.js';
import type { DeviceInfo, PrincipalBinding } from './types.js';

const endpoint = new URL('http://mcp-security.test/mcp');
const ownerId = 'security-owner';
const binding: PrincipalBinding = {
  clientId: 'security-client',
  principalId: 'security-owner',
  principalKind: 'owner',
  displayName: 'Security Owner',
  deviceOwnerPrincipalId: ownerId,
  allowedDeviceIds: ['security-phone'],
  scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request']
};
const phone: DeviceInfo = {
  id: 'security-phone',
  ownerPrincipalId: ownerId,
  kind: 'PHONE',
  platform: 'ANDROID',
  displayName: 'Security Phone',
  presence: 'ONLINE',
  controlProfile: 'ASK_EACH_ACTION',
  availableCapabilities: ['OPEN_APP'],
  grantedCapabilities: ['OPEN_APP'],
  effectiveCapabilities: ['OPEN_APP']
};

class ExplodingBackend extends MemoryUltronBackend {
  override async listDevices(): Promise<readonly DeviceInfo[]> {
    throw new Error('postgres://user:super-secret@internal-db/private?token=top-secret');
  }
}

const backend = new ExplodingBackend([phone]);
const assistant = {
  async handle() {
    return { state: 'ANSWERED', answer: 'bounded answer', taskProposal: null, usedMemoryRefs: [] };
  }
} as unknown as MainAssistantOrchestrator;
const assistantLimiter = new SubmissionRateLimiter(1, 60_000);
const handler = createMcpHandler(
  () => createUltronMcpServer(backend, binding, undefined, assistant, assistantLimiter),
  { legacy: 'stateless' }
);
const client = new Client({ name: 'security-boundary-test', version: '1.0.0' });
const transport = new StreamableHTTPClientTransport(endpoint, {
  fetch: async (input, init) => handler.fetch(new Request(input, init))
});

try {
  await client.connect(transport);

  const first = await client.callTool({ name: 'ultron.assistant', arguments: { message: 'hello' } });
  assert.notEqual(first.isError, true, 'first bounded assistant call must be admitted');

  const second = await client.callTool({ name: 'ultron.assistant', arguments: { message: 'again' } });
  assert.equal(second.isError, true, 'assistant provider calls must be rate bounded per principal');
  assert.equal(toolText(second), 'rate_limited');

  const exploded = await client.callTool({ name: 'ultron.list_devices', arguments: {} });
  assert.equal(exploded.isError, true);
  const text = toolText(exploded);
  assert.equal(text, 'device_list_failed');
  assert.equal(text.includes('super-secret'), false);
  assert.equal(text.includes('top-secret'), false);
  assert.equal(text.includes('internal-db'), false);

  console.error('[ultron-mcp] MCP security boundary self-test PASS');
} finally {
  await client.close();
  await handler.close();
}

function toolText(result: { content?: Array<{ type: string; text?: string }> }): string {
  return result.content?.find(item => item.type === 'text')?.text ?? '';
}
