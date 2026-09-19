import assert from 'node:assert/strict';
import { Pool } from 'pg';

import { DeviceTokenRegistry } from './device-auth.js';
import { PostgresBridgeUltronBackend } from './postgres-bridge.js';
import type { PrincipalBinding } from './types.js';

async function main(): Promise<void> {
  const connectionString = process.env.ULTRON_TEST_DATABASE_URL?.trim();
  if (!connectionString) throw new Error('ULTRON_TEST_DATABASE_URL is required');

  const admin = new Pool({ connectionString, max: 1 });
  await admin.query('DROP TABLE IF EXISTS ultron_bridge_tasks');
  await admin.query('DROP TABLE IF EXISTS ultron_bridge_devices');
  await admin.end();

  const ownerId = 'owner-postgres-test';
  const agent: PrincipalBinding = {
    clientId: 'frontend-client',
    principalId: 'frontend-agent',
    principalKind: 'agent',
    displayName: 'Frontend Agent',
    deviceOwnerPrincipalId: ownerId,
    allowedDeviceIds: ['phone-main'],
    scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request']
  };
  const device = new DeviceTokenRegistry({
    'postgres-device-token': {
      deviceId: 'phone-main',
      ownerPrincipalId: ownerId,
      kind: 'PHONE',
      displayName: 'Main Android',
      allowedCapabilities: ['OPEN_APP', 'UI_CLICK'],
      controlProfile: 'ASK_EACH_ACTION'
    }
  }).resolveToken('postgres-device-token');
  assert.ok(device);

  const first = new PostgresBridgeUltronBackend({ connectionString, maxConnections: 3 });
  const waiting = await first.submitTask(agent, {
    objective: 'Open Telegram and inspect the target chat',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: ['Do not send anything'],
    idempotencyKey: 'durable-1'
  });
  assert.equal(waiting.state, 'WAITING_FOR_DEVICE');

  const heartbeat = await first.deviceHeartbeat(device, {
    platform: 'ANDROID-37',
    availableCapabilities: ['OPEN_APP', 'UI_CLICK', 'UNSCOPED_CAPABILITY'],
    grantedCapabilities: ['OPEN_APP', 'UI_CLICK', 'UNSCOPED_CAPABILITY']
  });
  assert.deepEqual([...heartbeat.effectiveCapabilities].sort(), ['OPEN_APP', 'UI_CLICK']);
  assert.equal(heartbeat.effectiveCapabilities.includes('UNSCOPED_CAPABILITY'), false);

  const assigned = await first.getTask(agent, waiting.taskId);
  assert.equal(assigned?.state, 'RECEIVED');
  await first.close();

  const afterRestart = new PostgresBridgeUltronBackend({ connectionString, maxConnections: 3 });
  const restored = await afterRestart.getTask(agent, waiting.taskId);
  assert.equal(restored?.state, 'RECEIVED', 'queued task must survive server restart');
  const duplicate = await afterRestart.submitTask(agent, {
    objective: '  Open Telegram   and inspect the target chat  ',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: ['Do not send anything'],
    idempotencyKey: 'durable-1'
  });
  assert.equal(duplicate.taskId, waiting.taskId, 'same semantic request must dedupe after restart');

  await assert.rejects(
    () => afterRestart.submitTask(agent, {
      objective: 'Open Settings instead',
      target: 'phone',
      requiredCapabilities: ['OPEN_APP'],
      constraints: ['Do not send anything'],
      idempotencyKey: 'durable-1'
    }),
    /Idempotency key was already used for a different task request/,
    'same key with a different semantic request must conflict after restart'
  );

  const secondInstance = new PostgresBridgeUltronBackend({ connectionString, maxConnections: 3 });
  const concurrentRequest = {
    objective: 'Concurrent idempotent submit',
    target: 'cloud' as const,
    requiredCapabilities: [] as string[],
    constraints: [] as string[],
    idempotencyKey: 'concurrent-dedupe'
  };
  const [dedupeA, dedupeB] = await Promise.all([
    afterRestart.submitTask(agent, concurrentRequest),
    secondInstance.submitTask(agent, concurrentRequest)
  ]);
  assert.equal(dedupeA.taskId, dedupeB.taskId, 'concurrent identical submissions must collapse to one durable task');
  assert.equal(dedupeA.state, 'WAITING_FOR_DEVICE');
  assert.equal(dedupeB.state, 'WAITING_FOR_DEVICE');

  const conflictResults = await Promise.allSettled([
    afterRestart.submitTask(agent, {
      objective: 'Concurrent request A',
      target: 'cloud',
      requiredCapabilities: [],
      constraints: [],
      idempotencyKey: 'concurrent-conflict'
    }),
    secondInstance.submitTask(agent, {
      objective: 'Concurrent request B',
      target: 'cloud',
      requiredCapabilities: [],
      constraints: [],
      idempotencyKey: 'concurrent-conflict'
    })
  ]);
  assert.equal(conflictResults.filter(result => result.status === 'fulfilled').length, 1, 'exactly one conflicting request may own the key');
  const rejected = conflictResults.find(result => result.status === 'rejected');
  assert.ok(rejected && rejected.status === 'rejected');
  assert.match(String(rejected.reason), /Idempotency key was already used for a different task request/);

  const [claimA, claimB] = await Promise.all([
    afterRestart.deviceClaimNext(device),
    secondInstance.deviceClaimNext(device)
  ]);
  const claims = [claimA, claimB].filter((claim): claim is NonNullable<typeof claim> => claim !== undefined);
  assert.equal(claims.length, 1, 'two cloud instances must not double-claim one task');
  assert.equal(claims[0]?.taskId, waiting.taskId);

  await assert.rejects(
    () => afterRestart.deviceUpdateTask(device, waiting.taskId, { state: 'VERIFIED_DONE', narration: 'Done' }),
    /requires evidence/
  );
  const done = await afterRestart.deviceUpdateTask(device, waiting.taskId, {
    state: 'VERIFIED_DONE',
    narration: 'Target chat opened and verified',
    evidence: ['screen_state:telegram_chat_visible']
  });
  assert.equal(done.state, 'VERIFIED_DONE');
  assert.deepEqual(done.evidence, ['screen_state:telegram_chat_visible']);

  const queued = await afterRestart.submitTask(agent, {
    objective: 'Inspect settings and wait',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: []
  });
  const controlLease = await secondInstance.deviceClaimNext(device);
  assert.equal(controlLease?.taskId, queued.taskId);
  await afterRestart.pauseTask(agent, queued.taskId);
  assert.equal((await secondInstance.deviceInspectTask(device, queued.taskId)).state, 'PAUSED');
  await afterRestart.resumeTask(agent, queued.taskId);
  assert.equal((await secondInstance.deviceInspectTask(device, queued.taskId)).state, 'RUNNING');
  await afterRestart.cancelTask(agent, queued.taskId);
  assert.equal((await secondInstance.deviceInspectTask(device, queued.taskId)).state, 'CANCELLED');

  const control = await afterRestart.requestControl(agent, 'phone-main', 'MAX_APPROVED', 'Need UI automation');
  assert.equal(control.state, 'WAITING_FOR_OWNER');
  assert.equal(control.ownerApprovalRequired, true);

  const health = await afterRestart.health();
  assert.equal(health.ok, true);
  assert.match(health.detail, /durable bridge ready/);

  await secondInstance.close();
  await afterRestart.close();
  console.error('[ultron-mcp] postgres bridge self-test PASS');
}

void main().catch(error => {
  console.error('[ultron-mcp] postgres bridge self-test FAIL', error);
  process.exitCode = 1;
});
