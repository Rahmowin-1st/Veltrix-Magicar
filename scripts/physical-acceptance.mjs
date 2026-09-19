#!/usr/bin/env node
import { setTimeout as delay } from 'node:timers/promises';

const DEFAULT_BASE_URL = 'https://veltrix-ultron-mcp.onrender.com';
const DEFAULT_TIMEOUT_SECONDS = 600;
const HTTP_TIMEOUT_MS = 15_000;
const MAX_RESPONSE_BYTES = 1_048_576;
const POLL_MS = 1_000;

class AcceptanceError extends Error {}

function requiredEnv(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new AcceptanceError(`Missing required environment variable: ${name}`);
  return value;
}

function parseSha(value, name) {
  const normalized = value.trim().toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(normalized)) throw new AcceptanceError(`${name} must be an exact 40-character Git SHA`);
  return normalized;
}

function parseBaseUrl(raw) {
  let url;
  try { url = new URL(raw); } catch { throw new AcceptanceError('VELTRIX_ACCEPTANCE_BASE_URL is invalid'); }
  const localHttp = process.env.VELTRIX_ACCEPTANCE_ALLOW_HTTP_LOCALHOST === 'true' &&
    url.protocol === 'http:' && (url.hostname === '127.0.0.1' || url.hostname === 'localhost');
  if (url.protocol !== 'https:' && !localHttp) throw new AcceptanceError('Acceptance backend must use HTTPS');
  if (url.username || url.password || url.search || url.hash) throw new AcceptanceError('Acceptance backend URL must not contain credentials, query, or fragment');
  if (url.pathname !== '/' && url.pathname !== '') throw new AcceptanceError('Acceptance backend URL must point to the service root');
  return url.origin;
}

function parseTimeoutSeconds(raw) {
  if (!raw) return DEFAULT_TIMEOUT_SECONDS;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 60 || value > 900) throw new AcceptanceError('VELTRIX_ACCEPTANCE_TIMEOUT_SECONDS must be an integer from 60 to 900');
  return value;
}

function acceptanceConfig() {
  const branch = process.env.CIRCLE_BRANCH?.trim() || '';
  const branchMatch = branch.match(/^acceptance\/([0-9a-fA-F]{40})--([0-9a-fA-F]{40})$/);
  const backendShaRaw = process.env.VELTRIX_ACCEPTANCE_EXPECTED_BACKEND_SHA?.trim() || branchMatch?.[1];
  const deviceShaRaw = process.env.VELTRIX_ACCEPTANCE_EXPECTED_DEVICE_SHA?.trim() || branchMatch?.[2];
  if (!backendShaRaw) throw new AcceptanceError('Missing VELTRIX_ACCEPTANCE_EXPECTED_BACKEND_SHA or acceptance/<backendSha>--<deviceSha> branch metadata');
  if (!deviceShaRaw) throw new AcceptanceError('Missing VELTRIX_ACCEPTANCE_EXPECTED_DEVICE_SHA or acceptance/<backendSha>--<deviceSha> branch metadata');
  return {
    baseUrl: parseBaseUrl(process.env.VELTRIX_ACCEPTANCE_BASE_URL?.trim() || DEFAULT_BASE_URL),
    principalToken: requiredEnv('VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN'),
    expectedBackendSha: parseSha(backendShaRaw, 'expected backend SHA'),
    expectedDeviceSha: parseSha(deviceShaRaw, 'expected device SHA'),
    expectedDeviceId: process.env.VELTRIX_ACCEPTANCE_DEVICE_ID?.trim() || null,
    timeoutMs: parseTimeoutSeconds(process.env.VELTRIX_ACCEPTANCE_TIMEOUT_SECONDS) * 1_000,
    requireTakeover: (process.env.VELTRIX_ACCEPTANCE_REQUIRE_TAKEOVER?.trim().toLowerCase() || 'true') !== 'false'
  };
}

async function jsonRequest(config, path, { method = 'GET', body, auth = true, expected = [200] } = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), HTTP_TIMEOUT_MS);
  try {
    const headers = { Accept: 'application/json' };
    if (auth) headers.Authorization = `Bearer ${config.principalToken}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${config.baseUrl}${path}`, {
      method,
      headers,
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      signal: controller.signal,
      redirect: 'error'
    });
    const text = await boundedText(response);
    if (!expected.includes(response.status)) throw new AcceptanceError(`${method} ${path} returned HTTP ${response.status}`);
    if (!text) return null;
    try { return JSON.parse(text); } catch { throw new AcceptanceError(`${method} ${path} returned invalid JSON`); }
  } catch (error) {
    if (error instanceof AcceptanceError) throw error;
    if (error?.name === 'AbortError') throw new AcceptanceError(`${method} ${path} timed out`);
    throw new AcceptanceError(`${method} ${path} failed`);
  } finally {
    clearTimeout(timeout);
  }
}

async function boundedText(response) {
  const contentLength = Number(response.headers.get('content-length') || '0');
  if (Number.isFinite(contentLength) && contentLength > MAX_RESPONSE_BYTES) throw new AcceptanceError('Acceptance response exceeded size bound');
  const text = await response.text();
  if (Buffer.byteLength(text, 'utf8') > MAX_RESPONSE_BYTES) throw new AcceptanceError('Acceptance response exceeded size bound');
  return text;
}

function fixedLog(message) {
  console.log(`[veltrix-acceptance] ${message}`);
}

async function verifyHealth(config) {
  const health = await jsonRequest(config, '/health', { auth: false });
  if (!health || health.ok !== true) throw new AcceptanceError('Production health is not OK');
  if (String(health.releaseSha || '').toLowerCase() !== config.expectedBackendSha) {
    throw new AcceptanceError('Production release SHA does not match expected backend source');
  }
  fixedLog('backend release verified');
}

async function selectDevice(config) {
  const devices = await jsonRequest(config, '/v1/devices');
  if (!Array.isArray(devices)) throw new AcceptanceError('Device list response is invalid');
  const marker = `build=${config.expectedDeviceSha}`;
  let candidates = devices.filter(device =>
    device && device.kind === 'PHONE' && device.presence === 'ONLINE' &&
    typeof device.platform === 'string' && device.platform.toLowerCase().includes(marker) &&
    Array.isArray(device.effectiveCapabilities) && device.effectiveCapabilities.includes('OPEN_APP')
  );
  if (config.expectedDeviceId) candidates = candidates.filter(device => device.id === config.expectedDeviceId);
  if (candidates.length !== 1) throw new AcceptanceError(`Expected exactly one matching online phone, found ${candidates.length}`);
  fixedLog('exact-build online phone verified');
  return candidates[0];
}

async function forceAskEachAction(config, device) {
  const receipt = await jsonRequest(config, `/v1/devices/${encodeURIComponent(device.id)}/control-requests`, {
    method: 'POST',
    body: {
      profile: 'ASK_EACH_ACTION',
      reason: 'V2.45 physical acceptance requires explicit user approval for the benign proof mission'
    }
  });
  if (!receipt || receipt.state !== 'UPDATED' || receipt.effectiveProfile !== 'ASK_EACH_ACTION' || receipt.ownerApprovalRequired !== false) {
    throw new AcceptanceError('Acceptance principal is not authorized to set ASK_EACH_ACTION on the target phone');
  }
  fixedLog('explicit-approval control profile verified');
}

function missionBody(device, suffix) {
  return {
    objective: 'Open Android Settings and leave it visible. Do not change any setting or interact with protected content.',
    target: 'phone',
    deviceId: device.id,
    requiredCapabilities: ['OPEN_APP'],
    constraints: [
      'Require explicit user approval before execution.',
      'Do not type text or change any setting.',
      'Do not bypass PIN, password, biometric, CAPTCHA, OS security, or app security controls.',
      'Finish only with observable evidence that Android Settings is visible.'
    ],
    idempotencyKey: `v2.45-${suffix}-${Date.now()}`
  };
}

async function submitMission(config, device, suffix) {
  const receipt = await jsonRequest(config, '/v1/tasks', {
    method: 'POST',
    body: missionBody(device, suffix),
    expected: [202]
  });
  if (!receipt || typeof receipt.taskId !== 'string') throw new AcceptanceError('Task submission returned an invalid receipt');
  fixedLog(`${suffix} mission submitted`);
  return receipt.taskId;
}

async function task(config, taskId) {
  return jsonRequest(config, `/v1/tasks/${encodeURIComponent(taskId)}`);
}

async function waitForState(config, taskId, wanted, deadline, { forbidTerminal = true } = {}) {
  let last = null;
  while (Date.now() < deadline) {
    const receipt = await task(config, taskId);
    const state = receipt?.state;
    if (state !== last && typeof state === 'string') {
      fixedLog(`task state=${state}`);
      last = state;
    }
    if (wanted.includes(state)) return receipt;
    if (forbidTerminal && ['VERIFIED_DONE', 'FAILED', 'CANCELLED'].includes(state)) {
      throw new AcceptanceError(`Task became terminal before required state: ${state}`);
    }
    await delay(POLL_MS);
  }
  throw new AcceptanceError(`Timed out waiting for task state ${wanted.join('|')}`);
}

async function waitForMissionDone(config, taskId, deadline) {
  let sawWaiting = false;
  let last = null;
  while (Date.now() < deadline) {
    const receipt = await task(config, taskId);
    const state = receipt?.state;
    if (state !== last && typeof state === 'string') {
      fixedLog(`task state=${state}`);
      last = state;
    }
    if (state === 'WAITING_FOR_USER') {
      if (!sawWaiting) fixedLog('PHONE_ACTION=approve the harmless Settings-open mission in Veltrix');
      sawWaiting = true;
    }
    if (state === 'VERIFIED_DONE') {
      if (!sawWaiting) throw new AcceptanceError('Mission completed without an observed WAITING_FOR_USER permission gate');
      if (!Array.isArray(receipt.evidence) || receipt.evidence.length === 0) throw new AcceptanceError('VERIFIED_DONE did not contain evidence');
      fixedLog(`mission verified with ${receipt.evidence.length} evidence item(s)`);
      return;
    }
    if (state === 'FAILED' || state === 'CANCELLED') throw new AcceptanceError(`Mission terminated in state ${state}`);
    await delay(POLL_MS);
  }
  throw new AcceptanceError('Timed out waiting for physical mission completion');
}

async function exerciseOwnerControls(config, device, deadline) {
  const taskId = await submitMission(config, device, 'controls');
  await waitForState(config, taskId, ['WAITING_FOR_USER'], deadline);

  const paused = await jsonRequest(config, `/v1/tasks/${encodeURIComponent(taskId)}/pause`, { method: 'POST' });
  if (paused?.state !== 'PAUSED') throw new AcceptanceError('Remote Pause did not produce PAUSED');
  fixedLog('remote Pause verified');

  const resumed = await jsonRequest(config, `/v1/tasks/${encodeURIComponent(taskId)}/resume`, { method: 'POST' });
  if (!['RUNNING', 'WAITING_FOR_DEVICE'].includes(resumed?.state)) throw new AcceptanceError('Remote Resume did not produce a resumable device state');
  fixedLog('remote Resume verified');

  if (config.requireTakeover) {
    await waitForState(config, taskId, ['RUNNING', 'WAITING_FOR_USER'], deadline, { forbidTerminal: true });
    fixedLog('PHONE_ACTION=tap Take Over for the controls mission; do not approve it');
    await waitForState(config, taskId, ['PAUSED'], deadline, { forbidTerminal: true });
    fixedLog('local Take Over propagation verified');
  }

  const cancelled = await jsonRequest(config, `/v1/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' });
  if (cancelled?.state !== 'CANCELLED') throw new AcceptanceError('Remote Cancel did not produce CANCELLED');
  fixedLog('remote Cancel verified');
}

async function run() {
  const config = acceptanceConfig();
  const deadline = Date.now() + config.timeoutMs;
  fixedLog('V2.45 physical acceptance started');
  await verifyHealth(config);
  const device = await selectDevice(config);
  await forceAskEachAction(config, device);
  await exerciseOwnerControls(config, device, deadline);
  const proofTask = await submitMission(config, device, 'proof');
  await waitForMissionDone(config, proofTask, deadline);
  fixedLog('V2.45 PHYSICAL ACCEPTANCE PASS');
}

run().catch(error => {
  const message = error instanceof AcceptanceError ? error.message : 'Unexpected acceptance failure';
  console.error(`[veltrix-acceptance] FAIL: ${message}`);
  process.exitCode = 1;
});
