import assert from 'node:assert/strict';

import { DeviceTokenRegistry } from './device-auth.js';
import { PostgresBridgeUltronBackend } from './postgres-bridge.js';
import type { PrincipalBinding } from './types.js';

async function main(): Promise<void> {
  const connectionString = process.env.ULTRON_TEST_DATABASE_URL?.trim();
  if (!connectionString) throw new Error('ULTRON_TEST_DATABASE_URL is required');

  const ownerId = 'owner-authority-postgres';
  const principal: PrincipalBinding = {
    clientId: 'owner-authority-client',
    principalId: ownerId,
    principalKind: 'owner',
    displayName: 'Owner',
    deviceOwnerPrincipalId: ownerId,
    allowedDeviceIds: ['phone-authority'],
    scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request']
  };
  const device = new DeviceTokenRegistry({
    'owner-authority-device-token': {
      deviceId: 'phone-authority',
      ownerPrincipalId: ownerId,
      kind: 'PHONE',
      displayName: 'Authority Phone',
      allowedCapabilities: ['OPEN_APP'],
      controlProfile: 'ASK_EACH_ACTION'
    }
  }).resolveToken('owner-authority-device-token');
  assert.ok(device);

  const backend = new PostgresBridgeUltronBackend({ connectionString, maxConnections: 2 });
  try {
    await backend.deviceHeartbeat(device, {
      platform: 'ANDROID-37;build=authority-test',
      availableCapabilities: ['OPEN_APP'],
      grantedCapabilities: ['OPEN_APP']
    });
    const submitted = await backend.submitTask(principal, {
      objective: 'Open Settings only after approval',
      target: 'phone',
      requiredCapabilities: ['OPEN_APP'],
      constraints: [],
      idempotencyKey: `owner-authority-${Date.now()}`
    });
    const lease = await backend.deviceClaimNext(device);
    assert.equal(lease?.taskId, submitted.taskId);
    assert.equal(lease?.state, 'RUNNING');

    const paused = await backend.pauseTask(principal, submitted.taskId);
    assert.equal(paused.state, 'PAUSED');
    await assert.rejects(
      () => backend.deviceUpdateTask(device, submitted.taskId, {
        state: 'RUNNING',
        narration: 'late callback from pre-pause execution'
      }),
      /resumed by the authenticated principal/,
      'stale device progress must not undo durable owner Pause'
    );
    assert.equal((await backend.deviceInspectTask(device, submitted.taskId)).state, 'PAUSED');

    const resumed = await backend.resumeTask(principal, submitted.taskId);
    assert.equal(resumed.state, 'RUNNING');
    const waiting = await backend.deviceUpdateTask(device, submitted.taskId, {
      state: 'WAITING_FOR_USER',
      narration: 'Explicit approval required'
    });
    assert.equal(waiting.state, 'WAITING_FOR_USER');
    const cancelled = await backend.cancelTask(principal, submitted.taskId);
    assert.equal(cancelled.state, 'CANCELLED');

    console.error('[ultron-mcp] postgres owner-authority self-test PASS');
  } finally {
    await backend.close();
  }
}

void main().catch(error => {
  console.error('[ultron-mcp] postgres owner-authority self-test FAIL', error);
  process.exitCode = 1;
});
