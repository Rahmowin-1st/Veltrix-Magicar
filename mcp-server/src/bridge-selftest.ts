import assert from 'node:assert/strict';

import { BridgeUltronBackend } from './bridge.js';
import { DeviceTokenRegistry } from './device-auth.js';
import type { PrincipalBinding } from './types.js';

async function main(): Promise<void> {
  const ownerId = 'owner-bridge-test';
  const agent: PrincipalBinding = {
    clientId: 'frontend-client',
    principalId: 'frontend-agent',
    principalKind: 'agent',
    displayName: 'Frontend Agent',
    deviceOwnerPrincipalId: ownerId,
    allowedDeviceIds: ['phone-main'],
    scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request']
  };

  const deviceToken = 'device-token-placeholder';
  const devices = new DeviceTokenRegistry({
    [deviceToken]: {
      deviceId: 'phone-main',
      ownerPrincipalId: ownerId,
      kind: 'PHONE',
      displayName: 'Main Android',
      allowedCapabilities: ['OPEN_APP', 'UI_CLICK'],
      controlProfile: 'ASK_EACH_ACTION',
      expiresAt: Math.floor(Date.now() / 1000) + 600
    }
  });
  const device = devices.resolveToken(deviceToken);
  assert.ok(device);
  assert.equal(devices.resolveToken('wrong-token'), undefined);

  const backend = new BridgeUltronBackend();
  const waiting = await backend.submitTask(agent, {
    objective: 'Open Telegram and inspect the target chat',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: ['Do not send anything'],
    idempotencyKey: 'bridge-1'
  });
  assert.equal(waiting.state, 'WAITING_FOR_DEVICE');

  const heartbeat = backend.deviceHeartbeat(device, {
    platform: 'ANDROID-37',
    availableCapabilities: ['OPEN_APP', 'UI_CLICK', 'UNSCOPED_CAPABILITY'],
    grantedCapabilities: ['OPEN_APP', 'UI_CLICK', 'UNSCOPED_CAPABILITY']
  });
  assert.deepEqual([...heartbeat.effectiveCapabilities].sort(), ['OPEN_APP', 'UI_CLICK']);
  assert.equal(heartbeat.effectiveCapabilities.includes('UNSCOPED_CAPABILITY'), false, 'device token scope must cap claimed capabilities');

  const assigned = await backend.getTask(agent, waiting.taskId);
  assert.equal(assigned?.state, 'RECEIVED');
  assert.equal(assigned?.deviceId, 'phone-main');

  const lease = backend.deviceClaimNext(device);
  assert.equal(lease?.taskId, waiting.taskId);
  assert.equal(lease?.state, 'RUNNING');
  assert.equal(lease?.principalId, agent.principalId);
  assert.equal(lease?.principalKind, agent.principalKind);
  assert.equal(lease?.principalDisplayName, agent.displayName);
  assert.deepEqual(lease?.constraints, ['Do not send anything']);
  assert.equal(lease?.controlProfile, 'ASK_EACH_ACTION', 'device control profile must travel with the trusted task lease');

  assert.throws(
    () => backend.deviceUpdateTask(device, waiting.taskId, {
      state: 'VERIFIED_DONE',
      narration: 'Done'
    }),
    /requires evidence/,
    'device must not claim VERIFIED_DONE without evidence'
  );

  const done = backend.deviceUpdateTask(device, waiting.taskId, {
    state: 'VERIFIED_DONE',
    narration: 'Target chat opened and verified',
    evidence: ['screen_state:telegram_chat_visible']
  });
  assert.equal(done.state, 'VERIFIED_DONE');
  assert.deepEqual(done.evidence, ['screen_state:telegram_chat_visible']);

  const controllable = await backend.submitTask(agent, {
    objective: 'Inspect settings and wait',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: []
  });
  const controlLease = backend.deviceClaimNext(device);
  assert.equal(controlLease?.taskId, controllable.taskId);
  assert.equal(controlLease?.controlProfile, 'ASK_EACH_ACTION');
  await backend.pauseTask(agent, controllable.taskId);
  assert.equal(backend.deviceInspectTask(device, controllable.taskId).state, 'PAUSED');
  assert.throws(
    () => backend.deviceUpdateTask(device, controllable.taskId, {
      state: 'RUNNING',
      narration: 'stale device callback'
    }),
    /resumed by the authenticated principal/,
    'a stale device callback must not undo owner Pause'
  );
  assert.equal(backend.deviceInspectTask(device, controllable.taskId).state, 'PAUSED');
  await backend.resumeTask(agent, controllable.taskId);
  assert.equal(backend.deviceInspectTask(device, controllable.taskId).state, 'RUNNING');
  await backend.cancelTask(agent, controllable.taskId);
  assert.equal(backend.deviceInspectTask(device, controllable.taskId).state, 'CANCELLED');

  const control = await backend.requestControl(agent, 'phone-main', 'MAX_APPROVED', 'Need UI automation');
  assert.equal(control.state, 'WAITING_FOR_OWNER');
  assert.equal(control.ownerApprovalRequired, true);

  const duplicate = await backend.submitTask(agent, {
    objective: '  Open Telegram   and inspect the target chat  ',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: ['Do not send anything'],
    idempotencyKey: 'bridge-1'
  });
  assert.equal(duplicate.taskId, waiting.taskId, 'normalized semantic retry must deduplicate');

  await assert.rejects(
    () => backend.submitTask(agent, {
      objective: 'Open Settings instead',
      target: 'phone',
      requiredCapabilities: ['OPEN_APP'],
      constraints: ['Do not send anything'],
      idempotencyKey: 'bridge-1'
    }),
    /Idempotency key was already used for a different task request/,
    'same key must never be silently reinterpreted as another objective'
  );

  const health = await backend.health();
  assert.equal(health.ok, true);
  assert.match(health.detail, /bridge ready/);

  console.error('[ultron-mcp] bridge self-test PASS');
}

void main().catch(error => {
  console.error('[ultron-mcp] bridge self-test FAIL', error);
  process.exitCode = 1;
});
