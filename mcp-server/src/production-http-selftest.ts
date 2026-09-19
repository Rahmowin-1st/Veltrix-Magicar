import assert from 'node:assert/strict';
import { spawn, type ChildProcess } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const databaseUrl = process.env.ULTRON_TEST_DATABASE_URL?.trim();
if (!databaseUrl) throw new Error('ULTRON_TEST_DATABASE_URL is required');

const port = 18_787;
const baseUrl = `http://127.0.0.1:${port}`;
const principalToken = 'production-http-principal-token';
const deviceToken = 'production-http-device-token';
const releaseSha = '0123456789abcdef0123456789abcdef01234567';
const expiresAt = Math.floor(Date.now() / 1000) + 600;
const entry = fileURLToPath(new URL('./production-entry.js', import.meta.url));

let server: ChildProcess | undefined;
let stderr = '';
let stdout = '';

try {
  server = spawn(process.execPath, [entry], {
    env: {
      ...process.env,
      NODE_ENV: 'production',
      ULTRON_BACKEND: 'bridge',
      ULTRON_BRIDGE_STORE: 'postgres',
      DATABASE_URL: databaseUrl,
      ULTRON_MCP_HOST: '127.0.0.1',
      ULTRON_MCP_PORT: String(port),
      ULTRON_RELEASE_SHA: releaseSha,
      ULTRON_TASK_SUBMIT_RATE_PER_MINUTE: '3',
      ULTRON_AI_PLAN_RATE_PER_MINUTE: '2',
      ULTRON_PRINCIPAL_MUTATION_RATE_PER_MINUTE: '1',
      ULTRON_DEVICE_MUTATION_RATE_PER_MINUTE: '3',
      ULTRON_DEVICE_POLL_RATE_PER_MINUTE: '2',
      ULTRON_MCP_TOKENS_JSON: JSON.stringify({
        [principalToken]: {
          clientId: 'production-http-client',
          principalId: 'production-http-owner',
          principalKind: 'owner',
          displayName: 'Production HTTP Owner',
          deviceOwnerPrincipalId: 'production-http-owner',
          allowedDeviceIds: ['production-http-phone'],
          scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request'],
          expiresAt
        }
      }),
      ULTRON_DEVICE_TOKENS_JSON: JSON.stringify({
        [deviceToken]: {
          deviceId: 'production-http-phone',
          ownerPrincipalId: 'production-http-owner',
          kind: 'PHONE',
          displayName: 'Production HTTP Phone',
          allowedCapabilities: ['OPEN_APP'],
          controlProfile: 'ASK_EACH_ACTION',
          expiresAt
        }
      })
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  server.stdout?.on('data', chunk => { stdout += String(chunk); });
  server.stderr?.on('data', chunk => { stderr += String(chunk); });

  const health = await waitForHealth();
  assert.equal(health.ok, true);
  assert.equal(health.service, 'veltrix-ultron-mcp');
  assert.equal(health.version, '0.3.1');
  assert.equal(health.releaseSha, releaseSha);
  assert.equal(health.detail, 'ready', 'public health detail must not expose backend internals');

  const unauthenticatedPlannerStatus = await fetch(`${baseUrl}/v1/device/planner/status`);
  assert.equal(unauthenticatedPlannerStatus.status, 401, 'planner status must reject unauthenticated devices');
  assert.deepEqual(await unauthenticatedPlannerStatus.json(), { error: 'invalid_device_token' });

  const plannerStatus = await fetch(`${baseUrl}/v1/device/planner/status`, {
    headers: { Authorization: `Bearer ${deviceToken}` }
  });
  assert.equal(plannerStatus.status, 200);
  assert.deepEqual(await plannerStatus.json(), {
    enabled: false,
    providerCount: 0,
    visionProviderCount: 0
  });
  assert.equal(plannerStatus.headers.get('cache-control'), 'no-store');

  const plannerBody = {
    objective: 'Open a harmless app and verify it',
    constraints: ['Do not bypass user permission'],
    device: {
      id: 'production-http-phone',
      kind: 'PHONE',
      platform: 'ANDROID-TEST',
      effectiveCapabilities: ['OPEN_APP', 'UI_TYPE']
    },
    observation: {
      foregroundApp: null,
      foregroundWindow: null,
      uri: null,
      visibleText: []
    },
    memoryHints: [],
    recentEvidence: [],
    failedSteps: [],
    vision: null,
    previousSteps: [],
    replanReason: null
  };

  const wrongDevicePlanner = await fetch(`${baseUrl}/v1/device/planner/plan`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify({
      ...plannerBody,
      device: { ...plannerBody.device, id: 'other-phone' }
    })
  });
  assert.equal(wrongDevicePlanner.status, 403, 'device bearer must not plan for another device id');
  assert.deepEqual(await wrongDevicePlanner.json(), { error: 'device_binding_mismatch' });

  for (let attempt = 0; attempt < 2; attempt += 1) {
    const noProvider = await fetch(`${baseUrl}/v1/device/planner/plan`, {
      method: 'POST',
      headers: deviceHeaders(),
      body: JSON.stringify(plannerBody)
    });
    assert.equal(noProvider.status, 200);
    const noProviderResult = await noProvider.json() as { state: string; code?: string };
    assert.equal(noProviderResult.state, 'REJECTED');
    assert.equal(noProviderResult.code, 'AI_NOT_CONFIGURED');
    assert.equal(noProvider.headers.get('cache-control'), 'no-store');
  }

  const plannerRateLimited = await fetch(`${baseUrl}/v1/device/planner/plan`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify(plannerBody)
  });
  assert.equal(plannerRateLimited.status, 429, 'provider-triggering planner calls must be rate bounded per device');
  assert.deepEqual(await plannerRateLimited.json(), { error: 'rate_limited' });
  assert.ok(Number(plannerRateLimited.headers.get('retry-after')) >= 1);

  const unauthenticated = await fetch(`${baseUrl}/v1/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ objective: 'must be rejected', target: 'phone' })
  });
  assert.equal(unauthenticated.status, 401, 'task API must reject unauthenticated principals');

  const submitBody = {
    objective: 'Open a harmless app and verify it',
    target: 'phone',
    requiredCapabilities: ['OPEN_APP'],
    constraints: ['Do not change anything'],
    idempotencyKey: 'production-http-e2e'
  };
  const submit = await fetch(`${baseUrl}/v1/tasks`, {
    method: 'POST',
    headers: principalHeaders(),
    body: JSON.stringify(submitBody)
  });
  assert.equal(submit.status, 202);
  const submitted = await submit.json() as { taskId: string; state: string };
  assert.equal(submitted.state, 'WAITING_FOR_DEVICE');

  const retry = await fetch(`${baseUrl}/v1/tasks`, {
    method: 'POST',
    headers: principalHeaders(),
    body: JSON.stringify({ ...submitBody, objective: '  Open a harmless app   and verify it  ' })
  });
  assert.equal(retry.status, 202);
  const retried = await retry.json() as { taskId: string };
  assert.equal(retried.taskId, submitted.taskId, 'same normalized semantic request must dedupe');

  const conflict = await fetch(`${baseUrl}/v1/tasks`, {
    method: 'POST',
    headers: principalHeaders(),
    body: JSON.stringify({ ...submitBody, objective: 'Open Settings instead' })
  });
  assert.equal(conflict.status, 409, 'same key with different semantics must be an HTTP conflict');
  assert.deepEqual(await conflict.json(), { error: 'idempotency_conflict' });

  const rateLimited = await fetch(`${baseUrl}/v1/tasks`, {
    method: 'POST',
    headers: principalHeaders(),
    body: JSON.stringify({ ...submitBody, idempotencyKey: 'production-http-rate-limit' })
  });
  assert.equal(rateLimited.status, 429, 'authenticated objective submission must be rate bounded');
  assert.deepEqual(await rateLimited.json(), { error: 'rate_limited' });
  assert.ok(Number(rateLimited.headers.get('retry-after')) >= 1, 'rate limit response must include Retry-After');

  const heartbeatBody = {
    platform: 'ANDROID-TEST',
    availableCapabilities: ['OPEN_APP', 'UNSCOPED_CAPABILITY'],
    grantedCapabilities: ['OPEN_APP', 'UNSCOPED_CAPABILITY']
  };
  const heartbeat = await fetch(`${baseUrl}/v1/device/heartbeat`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify(heartbeatBody)
  });
  assert.equal(heartbeat.status, 200);
  const device = await heartbeat.json() as { effectiveCapabilities: string[] };
  assert.deepEqual(device.effectiveCapabilities, ['OPEN_APP'], 'device token scope must filter unapproved capabilities');

  const control = await fetch(`${baseUrl}/v1/devices/production-http-phone/control-requests`, {
    method: 'POST',
    headers: principalHeaders(),
    body: JSON.stringify({ profile: 'ASK_EACH_ACTION', reason: 'HTTP security self-test' })
  });
  assert.equal(control.status, 200);
  const controlRateLimited = await fetch(`${baseUrl}/v1/devices/production-http-phone/control-requests`, {
    method: 'POST',
    headers: principalHeaders(),
    body: JSON.stringify({ profile: 'ASK_EACH_ACTION', reason: 'repeat must be bounded' })
  });
  assert.equal(controlRateLimited.status, 429, 'state-changing principal endpoints must be rate bounded');
  assert.deepEqual(await controlRateLimited.json(), { error: 'rate_limited' });

  const wait = await fetch(`${baseUrl}/v1/device/tasks/wait`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify({ timeoutMs: 1_000 })
  });
  assert.equal(wait.status, 200);
  const leaseRoot = await wait.json() as { task: { taskId: string; state: string } | null };
  assert.equal(leaseRoot.task?.taskId, submitted.taskId);
  assert.equal(leaseRoot.task?.state, 'RUNNING');

  const noEvidence = await fetch(`${baseUrl}/v1/device/tasks/${encodeURIComponent(submitted.taskId)}/state`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify({ state: 'VERIFIED_DONE', narration: 'Done without proof' })
  });
  assert.equal(noEvidence.status, 409, 'VERIFIED_DONE must be rejected without evidence');

  const withEvidence = await fetch(`${baseUrl}/v1/device/tasks/${encodeURIComponent(submitted.taskId)}/state`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify({
      state: 'VERIFIED_DONE',
      narration: 'Verified by production HTTP self-test',
      evidence: ['test_evidence:harmless_app_visible']
    })
  });
  assert.equal(withEvidence.status, 200);

  const deviceMutationRateLimited = await fetch(`${baseUrl}/v1/device/heartbeat`, {
    method: 'POST',
    headers: deviceHeaders(),
    body: JSON.stringify(heartbeatBody)
  });
  assert.equal(deviceMutationRateLimited.status, 429, 'device mutation endpoints must share a bounded device budget');
  assert.deepEqual(await deviceMutationRateLimited.json(), { error: 'rate_limited' });

  const receiptResponse = await fetch(`${baseUrl}/v1/tasks/${encodeURIComponent(submitted.taskId)}`, {
    headers: { Authorization: `Bearer ${principalToken}` }
  });
  assert.equal(receiptResponse.status, 200);
  const receipt = await receiptResponse.json() as { state: string; evidence: string[] };
  assert.equal(receipt.state, 'VERIFIED_DONE');
  assert.deepEqual(receipt.evidence, ['test_evidence:harmless_app_visible']);

  console.error('[ultron-mcp] production HTTP self-test PASS');
} catch (error) {
  console.error('[ultron-mcp] production HTTP self-test FAIL');
  if (stderr) console.error(stderr);
  if (stdout) console.error(stdout);
  throw error;
} finally {
  if (server && server.exitCode === null) {
    server.kill('SIGTERM');
    await waitForExit(server);
  }
}

function principalHeaders(): Record<string, string> {
  return {
    Authorization: `Bearer ${principalToken}`,
    'Content-Type': 'application/json'
  };
}

function deviceHeaders(): Record<string, string> {
  return {
    Authorization: `Bearer ${deviceToken}`,
    'Content-Type': 'application/json'
  };
}

interface HealthResponse {
  service: string;
  version: string;
  releaseSha: string | null;
  ok: boolean;
  detail?: string;
}

async function waitForHealth(): Promise<HealthResponse> {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (!server || server.exitCode !== null) throw new Error(`Production server exited early with code ${server?.exitCode ?? 'not-started'}`);
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) return await response.json() as HealthResponse;
    } catch {
      // Cold start: retry within the bounded test window.
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
  throw new Error('Production server health check did not become ready');
}

function waitForExit(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null) return Promise.resolve();
  return new Promise(resolve => {
    child.once('exit', () => resolve());
    setTimeout(() => {
      if (child.exitCode === null) child.kill('SIGKILL');
      resolve();
    }, 3_000).unref();
  });
}
