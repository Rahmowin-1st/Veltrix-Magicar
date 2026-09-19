import assert from 'node:assert/strict';

import { TokenBindingRegistry } from './auth.js';
import { MemoryUltronBackend } from './backend.js';
import { DeviceTokenRegistry } from './device-auth.js';
import { assertProductionAuthPreflight } from './production-preflight.js';
import { ULTRON_TOOL_NAMES } from './server.js';
import type { DeviceInfo, PrincipalBinding } from './types.js';

async function main(): Promise<void> {
  const ownerId = 'owner-test';
  const agent: PrincipalBinding = {
    clientId: 'agent-client',
    principalId: 'frontend-agent',
    principalKind: 'agent',
    displayName: 'Frontend Agent',
    deviceOwnerPrincipalId: ownerId,
    allowedDeviceIds: ['phone-1'],
    scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request']
  };
  const otherAgent: PrincipalBinding = {
    ...agent,
    clientId: 'qa-client',
    principalId: 'qa-agent',
    displayName: 'QA Agent'
  };

  const phone: DeviceInfo = {
    id: 'phone-1',
    ownerPrincipalId: ownerId,
    kind: 'PHONE',
    platform: 'ANDROID',
    displayName: 'Test Phone',
    presence: 'ONLINE',
    controlProfile: 'MAX_APPROVED',
    availableCapabilities: ['OPEN_APP', 'UI_CLICK'],
    grantedCapabilities: ['OPEN_APP'],
    effectiveCapabilities: ['OPEN_APP']
  };
  const backend = new MemoryUltronBackend([phone]);

  const first = await backend.submitTask(agent, {
    objective: 'Open the target app and verify it',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: [],
    idempotencyKey: 'same-task'
  });
  const duplicate = await backend.submitTask(agent, {
    objective: '  Open the target app   and verify it  ',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: [],
    idempotencyKey: 'same-task'
  });
  assert.equal(first.taskId, duplicate.taskId, 'normalized idempotency retry must deduplicate per principal');
  assert.equal(first.deviceId, 'phone-1');

  await assert.rejects(
    () => backend.submitTask(agent, {
      objective: 'Open a different app',
      target: 'phone',
      requiredCapabilities: ['OPEN_APP'],
      constraints: [],
      idempotencyKey: 'same-task'
    }),
    /Idempotency key was already used for a different task request/,
    'same key with a different semantic request must conflict'
  );

  await assert.rejects(
    () => backend.getTask(otherAgent, first.taskId),
    /another authenticated principal/,
    'other principal must not read task'
  );

  const control = await backend.requestControl(agent, 'phone-1', 'MAX_APPROVED', 'Need autonomous QA');
  assert.equal(control.state, 'WAITING_FOR_OWNER');
  assert.equal(control.ownerApprovalRequired, true, 'agent cannot elevate device control');

  assert.ok(ULTRON_TOOL_NAMES.includes('ultron.assistant'));
  assert.ok(ULTRON_TOOL_NAMES.includes('ultron.submit_task'));
  assert.ok(ULTRON_TOOL_NAMES.includes('ultron.list_devices'));
  assert.equal(
    ULTRON_TOOL_NAMES.some(name => /(?:tap|type|shell|exec)/i.test(name)),
    false,
    'raw privileged executor primitives must not be exposed as MCP tools'
  );

  const now = Math.floor(Date.now() / 1000);
  const token = 'self-test-token-placeholder';
  const registry = new TokenBindingRegistry({
    [token]: {
      clientId: agent.clientId,
      principalId: agent.principalId,
      principalKind: agent.principalKind,
      displayName: agent.displayName,
      deviceOwnerPrincipalId: ownerId,
      allowedDeviceIds: ['phone-1'],
      scopes: [...agent.scopes],
      expiresAt: now + 600
    },
    'expired-self-test-token': {
      clientId: 'expired-client',
      principalId: 'expired-agent',
      principalKind: 'agent',
      displayName: 'Expired Agent',
      deviceOwnerPrincipalId: ownerId,
      allowedDeviceIds: ['phone-1'],
      scopes: ['mcp'],
      expiresAt: now - 1
    }
  });
  const resolved = registry.resolveToken(token);
  assert.equal(resolved?.principalId, agent.principalId);
  assert.equal(registry.resolveToken('wrong-token'), undefined);
  assert.equal(registry.activeCount(now), 1, 'expired MCP credentials must not satisfy production preflight');

  const acceptanceToken = 'ephemeral-acceptance-token';
  const acceptance = TokenBindingRegistry.fromEnv('', {
    ULTRON_OWNER_PRINCIPAL_ID: ownerId,
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN: acceptanceToken,
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN_EXPIRES_AT: String(now + 600)
  }, now);
  const acceptanceBinding = acceptance.resolveToken(acceptanceToken);
  assert.equal(acceptanceBinding?.principalId, ownerId);
  assert.equal(acceptanceBinding?.principalKind, 'owner');
  assert.deepEqual(acceptanceBinding?.scopes, ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request']);
  assert.equal(acceptance.activeCount(now), 1);
  assert.throws(() => TokenBindingRegistry.fromEnv('', {
    ULTRON_OWNER_PRINCIPAL_ID: ownerId,
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN: acceptanceToken,
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN_EXPIRES_AT: String(now + 7_201)
  }, now), /2-hour maximum/);
  const expiredAcceptance = TokenBindingRegistry.fromEnv('', {
    ULTRON_OWNER_PRINCIPAL_ID: ownerId,
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN: acceptanceToken,
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN_EXPIRES_AT: String(now - 1)
  }, now);
  assert.equal(expiredAcceptance.activeCount(now), 0, 'expired acceptance token must not remain active');

  const devices = new DeviceTokenRegistry({
    'active-device-token': {
      deviceId: 'phone-1',
      ownerPrincipalId: ownerId,
      kind: 'PHONE',
      displayName: 'Test Phone',
      allowedCapabilities: ['OPEN_APP'],
      expiresAt: now + 600
    },
    'expired-device-token': {
      deviceId: 'phone-old',
      ownerPrincipalId: ownerId,
      kind: 'PHONE',
      displayName: 'Expired Phone',
      allowedCapabilities: [],
      expiresAt: now - 1
    }
  });
  assert.equal(devices.activeCount(now), 1, 'expired device credentials must not satisfy production preflight');

  assert.doesNotThrow(() => assertProductionAuthPreflight({
    nodeEnv: 'development',
    backendMode: 'bridge',
    activePrincipalTokens: 0,
    activeDeviceTokens: 0
  }));
  assert.throws(() => assertProductionAuthPreflight({
    nodeEnv: 'production',
    backendMode: 'bridge',
    activePrincipalTokens: 0,
    activeDeviceTokens: 1
  }), /at least one active principal/);
  assert.throws(() => assertProductionAuthPreflight({
    nodeEnv: 'production',
    backendMode: 'bridge',
    activePrincipalTokens: 1,
    activeDeviceTokens: 0
  }), /at least one active device/);
  assert.doesNotThrow(() => assertProductionAuthPreflight({
    nodeEnv: 'production',
    backendMode: 'bridge',
    activePrincipalTokens: registry.activeCount(now),
    activeDeviceTokens: devices.activeCount(now)
  }));

  const health = await backend.health();
  assert.equal(health.ok, true);

  console.error('[ultron-mcp] self-test PASS');
}

void main().catch(error => {
  console.error('[ultron-mcp] self-test FAIL', error);
  process.exitCode = 1;
});
