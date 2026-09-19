#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const backendSha = '1111111111111111111111111111111111111111';
const deviceSha = '2222222222222222222222222222222222222222';
const token = 'selftest-owner-token-secret';
const SELFTEST_CHILD_TIMEOUT_MS = 15_000;
const tasks = new Map();
let nextTask = 1;

const server = createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://127.0.0.1');
  const send = (status, body) => {
    const json = JSON.stringify(body);
    res.writeHead(status, {
      'content-type': 'application/json',
      'content-length': Buffer.byteLength(json),
      connection: 'close'
    });
    res.end(json);
  };
  const auth = req.headers.authorization === `Bearer ${token}`;

  if (req.method === 'GET' && url.pathname === '/health') {
    return send(200, { service: 'veltrix-ultron-mcp', version: '0.3.1', releaseSha: backendSha, ok: true, detail: 'durable bridge ready; 1 device(s) online' });
  }
  if (!auth) return send(401, { error: 'invalid_token' });

  if (req.method === 'GET' && url.pathname === '/v1/devices') {
    return send(200, [{
      id: 'selftest-phone', ownerPrincipalId: 'owner', kind: 'PHONE', platform: `ANDROID-37;build=${deviceSha}`,
      displayName: 'Self-test Phone', presence: 'ONLINE', controlProfile: 'ASK_EACH_ACTION',
      availableCapabilities: ['OPEN_APP'], grantedCapabilities: ['OPEN_APP'], effectiveCapabilities: ['OPEN_APP']
    }]);
  }

  if (req.method === 'POST' && url.pathname === '/v1/devices/selftest-phone/control-requests') {
    await readJson(req);
    return send(200, { deviceId: 'selftest-phone', state: 'UPDATED', effectiveProfile: 'ASK_EACH_ACTION', ownerApprovalRequired: false, message: 'updated' });
  }

  if (req.method === 'POST' && url.pathname === '/v1/tasks') {
    await readJson(req);
    const taskId = `task-${nextTask++}`;
    tasks.set(taskId, { state: 'WAITING_FOR_USER', evidence: [], mode: taskId === 'task-1' ? 'controls' : 'proof', gets: 0, resumed: false });
    return send(202, receipt(taskId));
  }

  const match = url.pathname.match(/^\/v1\/tasks\/(task-\d+)(?:\/(pause|resume|cancel))?$/);
  if (match) {
    const [, taskId, op] = match;
    const task = tasks.get(taskId);
    if (!task) return send(404, { error: 'not_found' });
    if (req.method === 'POST' && op === 'pause') {
      task.state = 'PAUSED';
      return send(200, receipt(taskId));
    }
    if (req.method === 'POST' && op === 'resume') {
      task.state = 'RUNNING';
      task.resumed = true;
      task.gets = 0;
      return send(200, receipt(taskId));
    }
    if (req.method === 'POST' && op === 'cancel') {
      task.state = 'CANCELLED';
      return send(200, receipt(taskId));
    }
    if (req.method === 'GET' && !op) {
      task.gets += 1;
      if (task.mode === 'controls' && task.resumed && task.gets >= 2) task.state = 'PAUSED';
      if (task.mode === 'proof' && task.gets >= 2) {
        task.state = 'VERIFIED_DONE';
        task.evidence = ['selftest:settings_visible'];
      }
      return send(200, receipt(taskId));
    }
  }

  send(404, { error: 'not_found' });
});

function receipt(taskId) {
  const task = tasks.get(taskId);
  return {
    taskId,
    principalId: 'owner',
    state: task?.state || 'WAITING_FOR_USER',
    narration: 'self-test',
    deviceId: 'selftest-phone',
    evidence: task?.evidence || [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  };
}

async function readJson(req) {
  let text = '';
  for await (const chunk of req) text += String(chunk);
  return text ? JSON.parse(text) : null;
}

const listening = new Promise((resolvePromise, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', () => resolvePromise());
});
await listening;
const address = server.address();
assert.ok(address && typeof address === 'object');
const baseUrl = `http://127.0.0.1:${address.port}`;

try {
  const script = resolve(dirname(fileURLToPath(import.meta.url)), 'physical-acceptance.mjs');
  const result = await runNode(script, {
    VELTRIX_ACCEPTANCE_BASE_URL: baseUrl,
    VELTRIX_ACCEPTANCE_ALLOW_HTTP_LOCALHOST: 'true',
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN: token,
    VELTRIX_ACCEPTANCE_EXPECTED_BACKEND_SHA: backendSha,
    VELTRIX_ACCEPTANCE_EXPECTED_DEVICE_SHA: deviceSha,
    VELTRIX_ACCEPTANCE_TIMEOUT_SECONDS: '60',
    VELTRIX_ACCEPTANCE_REQUIRE_TAKEOVER: 'true'
  });
  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /V2\.45 PHYSICAL ACCEPTANCE PASS/);
  assert.match(result.stdout, /local Take Over propagation verified/);
  assert.match(result.stdout, /mission verified with 1 evidence item\(s\)/);
  assert.ok(!result.stdout.includes(token) && !result.stderr.includes(token), 'acceptance token must never be printed');

  const bad = await runNode(script, {
    VELTRIX_ACCEPTANCE_BASE_URL: baseUrl,
    VELTRIX_ACCEPTANCE_ALLOW_HTTP_LOCALHOST: 'true',
    VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN: token,
    VELTRIX_ACCEPTANCE_EXPECTED_BACKEND_SHA: '3333333333333333333333333333333333333333',
    VELTRIX_ACCEPTANCE_EXPECTED_DEVICE_SHA: deviceSha,
    VELTRIX_ACCEPTANCE_TIMEOUT_SECONDS: '60'
  });
  assert.notEqual(bad.code, 0);
  assert.match(bad.stderr, /Production release SHA does not match expected backend source/);
  assert.ok(!bad.stdout.includes(token) && !bad.stderr.includes(token), 'failure output must not print acceptance token');

  console.error('[veltrix-acceptance] self-test PASS');
} finally {
  await closeServer();
}

function runNode(script, extraEnv) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(process.execPath, [script], {
      env: { ...process.env, ...extraEnv },
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      if (child.exitCode === null) child.kill('SIGKILL');
    }, SELFTEST_CHILD_TIMEOUT_MS);
    child.stdout.on('data', chunk => { stdout += String(chunk); });
    child.stderr.on('data', chunk => { stderr += String(chunk); });
    child.once('error', error => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('exit', code => {
      clearTimeout(timer);
      resolvePromise({ code: code ?? 1, stdout, stderr });
    });
  });
}

function closeServer() {
  return new Promise(resolvePromise => {
    const timer = setTimeout(() => {
      server.closeAllConnections?.();
      resolvePromise();
    }, 2_000);
    server.close(() => {
      clearTimeout(timer);
      resolvePromise();
    });
    server.closeIdleConnections?.();
  });
}
