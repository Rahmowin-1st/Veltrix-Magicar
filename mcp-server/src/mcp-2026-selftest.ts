import assert from 'node:assert/strict';

import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { createMcpHandler } from '@modelcontextprotocol/server';

import { MemoryUltronBackend } from './backend.js';
import { createUltronMcpServer } from './server.js';
import type { DeviceInfo, PrincipalBinding } from './types.js';

const MODERN_REVISION = '2026-07-28';
const endpoint = new URL('http://mcp-2026.test/mcp');

async function main(): Promise<void> {
  const ownerId = 'modern-owner';
  const binding: PrincipalBinding = {
    clientId: 'modern-client-binding',
    principalId: 'modern-agent',
    principalKind: 'agent',
    displayName: 'Modern Agent',
    deviceOwnerPrincipalId: ownerId,
    allowedDeviceIds: ['phone-modern'],
    scopes: ['mcp', 'ultron:tasks', 'ultron:devices']
  };
  const phone: DeviceInfo = {
    id: 'phone-modern',
    ownerPrincipalId: ownerId,
    kind: 'PHONE',
    platform: 'ANDROID',
    displayName: 'Modern Test Phone',
    presence: 'ONLINE',
    controlProfile: 'ASK_EACH_ACTION',
    availableCapabilities: ['OPEN_APP'],
    grantedCapabilities: ['OPEN_APP'],
    effectiveCapabilities: ['OPEN_APP']
  };

  const backend = new MemoryUltronBackend([phone]);
  const handlerA = createMcpHandler(
    () => createUltronMcpServer(backend, binding),
    { legacy: 'stateless' }
  );
  const handlerB = createMcpHandler(
    () => createUltronMcpServer(backend, binding),
    { legacy: 'stateless' }
  );

  let submitWireRequest: Request | undefined;
  const seenA: Array<{ method: string | null; name: string | null }> = [];
  const seenB: Array<{ method: string | null; name: string | null }> = [];

  const clientA = modernClient('modern-client-a');
  const clientB = modernClient('modern-client-b');
  const transportA = new StreamableHTTPClientTransport(endpoint, {
    fetch: throughHandler(handlerA, seenA, request => {
      if (request.headers.get('mcp-name') === 'ultron.submit_task') {
        submitWireRequest = request.clone();
      }
    })
  });
  const transportB = new StreamableHTTPClientTransport(endpoint, {
    fetch: throughHandler(handlerB, seenB)
  });

  try {
    await clientA.connect(transportA);
    await clientB.connect(transportB);

    assert.equal(clientA.getProtocolEra(), 'modern');
    assert.equal(clientB.getProtocolEra(), 'modern');
    assert.ok(clientA.getDiscoverResult(), 'modern connect must complete through server/discover');
    assert.ok(clientB.getDiscoverResult(), 'second stateless instance must independently discover');

    const tools = await clientA.listTools();
    assert.ok(tools.tools.some(tool => tool.name === 'ultron.submit_task'));
    assert.ok(tools.tools.some(tool => tool.name === 'ultron.get_task'));

    const submitResult = await clientA.callTool({
      name: 'ultron.submit_task',
      arguments: {
        objective: 'Open a harmless test app and verify it',
        target: 'phone',
        device_id: 'phone-modern',
        required_capabilities: ['OPEN_APP'],
        constraints: ['Do not send anything'],
        idempotency_key: 'modern-cross-instance'
      }
    });
    const submitted = toolJson<{ taskId: string }>(submitResult);
    assert.ok(submitted.taskId);

    const readResult = await clientB.callTool({
      name: 'ultron.get_task',
      arguments: { task_id: submitted.taskId }
    });
    const read = toolJson<{ taskId: string }>(readResult);
    assert.equal(
      read.taskId,
      submitted.taskId,
      'task application state must survive routing the next MCP request to another handler instance'
    );

    assert.ok(
      seenA.some(entry => entry.method === 'server/discover'),
      'modern client must use server/discover instead of initialize'
    );
    assert.equal(
      seenA.some(entry => entry.method === 'initialize'),
      false,
      '2026-07-28 path must not require initialize'
    );
    assert.ok(
      seenA.some(entry => entry.method === 'tools/call' && entry.name === 'ultron.submit_task'),
      'modern tool calls must carry Mcp-Method and Mcp-Name routing headers'
    );
    assert.ok(
      seenB.some(entry => entry.method === 'tools/call' && entry.name === 'ultron.get_task'),
      'a second handler instance must receive a complete self-describing tool request'
    );

    const captured = submitWireRequest;
    assert.ok(captured, 'submit tool wire request must be captured');
    const mismatchHeaders = new Headers(captured.headers);
    mismatchHeaders.set('Mcp-Name', 'ultron.health');
    const mismatch = await handlerA.fetch(new Request(captured.url, {
      method: 'POST',
      headers: mismatchHeaders,
      body: await captured.clone().text()
    }));
    assert.equal(mismatch.status, 400, 'Mcp-Name/body mismatch must fail closed');
    assert.match(await mismatch.text(), /-32020/, 'header mismatch must use the modern HeaderMismatch code');

    console.error('[ultron-mcp] 2026-07-28 self-test PASS');
  } finally {
    await clientA.close();
    await clientB.close();
    await handlerA.close();
    await handlerB.close();
  }
}

function modernClient(name: string): Client {
  return new Client(
    { name, version: '1.0.0' },
    { versionNegotiation: { mode: { pin: MODERN_REVISION } } }
  );
}

function throughHandler(
  handler: ReturnType<typeof createMcpHandler>,
  seen: Array<{ method: string | null; name: string | null }>,
  inspect?: (request: Request) => void
): typeof fetch {
  return async (input, init) => {
    const request = new Request(input, init);
    const method = request.headers.get('mcp-method');
    const name = request.headers.get('mcp-name');
    seen.push({ method, name });

    if (method) {
      assert.equal(
        request.headers.get('mcp-protocol-version'),
        MODERN_REVISION,
        'every modern HTTP request must carry MCP-Protocol-Version'
      );
    }

    inspect?.(request);
    const response = await handler.fetch(request);
    assert.equal(
      response.headers.get('mcp-session-id'),
      null,
      '2026-07-28 must not mint or depend on Mcp-Session-Id'
    );
    return response;
  };
}

function toolJson<T>(result: { content?: Array<{ type: string; text?: string }> }): T {
  const text = result.content?.find(item => item.type === 'text')?.text;
  assert.ok(text, 'tool result must contain text JSON');
  return JSON.parse(text) as T;
}

void main().catch(error => {
  console.error('[ultron-mcp] 2026-07-28 self-test FAIL', error);
  process.exitCode = 1;
});
