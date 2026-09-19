import { createMcpExpressApp, requireBearerAuth } from '@modelcontextprotocol/express';
import { toNodeHandler } from '@modelcontextprotocol/node';
import { createMcpHandler } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';

import { BackendAiPlanner } from './ai-planner.js';
import { TokenBindingRegistry } from './auth.js';
import { backendFromEnv, type UltronBackend } from './backend.js';
import { BridgeUltronBackend } from './bridge.js';
import { DeviceTokenRegistry } from './device-auth.js';
import { isDeviceBridgeBackend, type DeviceBridgeBackend, type DeviceTaskUpdate } from './device-bridge.js';
import { DevicePlannerBindingError, parseAiPlanRateLimit, scopeDevicePlannerRequest } from './device-planner.js';
import { IdempotencyConflictError } from './idempotency.js';
import { GeminiLiveTokenIssuer } from './gemini-live-token.js';
import { PostgresBridgeUltronBackend } from './postgres-bridge.js';
import { safeHealthDetail, safePublicErrorCode } from './safe-errors.js';
import { SecureDeviceBootstrap } from './secure-bootstrap.js';
import { createHttpMcpFactory } from './server.js';
import {
  SubmissionRateLimiter,
  SubmissionRateLimitError,
  parseBoundedRateLimit,
  parseSubmissionRateLimit
} from './submission-rate-limit.js';
import type { DeviceBinding } from './device-auth.js';
import type { PrincipalBinding, SubmitTaskInput } from './types.js';

const host = process.env.ULTRON_MCP_HOST?.trim() || '127.0.0.1';
const port = parsePort(process.env.PORT ?? process.env.ULTRON_MCP_PORT ?? '8787');
const allowedHosts = splitCsv(process.env.ULTRON_MCP_ALLOWED_HOSTS ?? '');
const allowedOrigins = splitCsv(process.env.ULTRON_MCP_ALLOWED_ORIGINS ?? '');
const serviceVersion = '0.3.1';
const releaseSha = parseReleaseSha(process.env.ULTRON_RELEASE_SHA ?? process.env.RENDER_GIT_COMMIT);

if ((host === '0.0.0.0' || host === '::') && allowedHosts.length === 0) {
  throw new Error('ULTRON_MCP_ALLOWED_HOSTS is required when MCP binds to all interfaces');
}

const registry = TokenBindingRegistry.fromEnv();
const deviceRegistry = DeviceTokenRegistry.fromEnv();
const secureBootstrap = SecureDeviceBootstrap.fromEnv();
const backendAiPlanner = BackendAiPlanner.fromEnv();
const geminiLiveTokenIssuer = GeminiLiveTokenIssuer.fromEnv();
const backend = createBackend();
const deviceBridge = isDeviceBridgeBackend(backend) ? backend : undefined;
const submissionLimiter = new SubmissionRateLimiter(
  parseSubmissionRateLimit(process.env.ULTRON_TASK_SUBMIT_RATE_PER_MINUTE)
);
const assistantLimiter = new SubmissionRateLimiter(
  parseBoundedRateLimit(process.env.ULTRON_ASSISTANT_RATE_PER_MINUTE, 'ULTRON_ASSISTANT_RATE_PER_MINUTE', 30)
);
const principalMutationLimiter = new SubmissionRateLimiter(
  parseBoundedRateLimit(
    process.env.ULTRON_PRINCIPAL_MUTATION_RATE_PER_MINUTE,
    'ULTRON_PRINCIPAL_MUTATION_RATE_PER_MINUTE',
    120
  )
);
const deviceMutationLimiter = new SubmissionRateLimiter(
  parseBoundedRateLimit(
    process.env.ULTRON_DEVICE_MUTATION_RATE_PER_MINUTE,
    'ULTRON_DEVICE_MUTATION_RATE_PER_MINUTE',
    240
  )
);
const devicePollLimiter = new SubmissionRateLimiter(
  parseBoundedRateLimit(process.env.ULTRON_DEVICE_POLL_RATE_PER_MINUTE, 'ULTRON_DEVICE_POLL_RATE_PER_MINUTE', 60)
);
const aiPlanLimiter = new SubmissionRateLimiter(
  parseAiPlanRateLimit(process.env.ULTRON_AI_PLAN_RATE_PER_MINUTE)
);
const bootstrapLimiter = new SubmissionRateLimiter(20);
const pairingLimiter = new SubmissionRateLimiter(5);
const liveTokenLimiter = new SubmissionRateLimiter(20);
const factory = createHttpMcpFactory(
  backend,
  registry,
  submissionLimiter,
  undefined,
  assistantLimiter,
  principalMutationLimiter
);
const handler = createMcpHandler(factory);
const nodeHandler = toNodeHandler(handler);

const app = createMcpExpressApp({
  host,
  jsonLimit: '384kb',
  ...(allowedHosts.length > 0 ? { allowedHosts } : {}),
  ...(allowedOrigins.length > 0 ? { allowedOrigins } : {})
});

const auth = requireBearerAuth({ verifier: registry.verifier(), requiredScopes: ['mcp'] });

const submitTaskSchema = z.object({
  objective: z.string().min(1).max(16_000),
  target: z.enum(['auto', 'phone', 'desktop', 'cloud']).default('auto'),
  deviceId: z.string().min(1).max(200).optional(),
  requiredCapabilities: z.array(z.string().min(1).max(200)).max(64).default([]),
  constraints: z.array(z.string().max(2_000)).max(64).default([]),
  idempotencyKey: z.string().min(1).max(200).optional()
});
const controlSchema = z.object({
  profile: z.enum(['READ_ONLY', 'ASK_EACH_ACTION', 'MAX_APPROVED']),
  reason: z.string().min(1).max(1000)
});
const heartbeatSchema = z.object({
  platform: z.string().min(1).max(100),
  availableCapabilities: z.array(z.string().min(1).max(200)).max(256),
  grantedCapabilities: z.array(z.string().min(1).max(200)).max(256)
});
const waitSchema = z.object({
  timeoutMs: z.number().int().min(1_000).max(30_000).default(25_000)
});
const deviceUpdateSchema = z.object({
  state: z.enum(['RUNNING', 'WAITING_FOR_USER', 'PAUSED', 'VERIFIED_DONE', 'FAILED', 'CANCELLED']),
  narration: z.string().min(1).max(2000),
  evidence: z.array(z.string().min(1).max(2000)).max(128).optional()
});
const enrollmentChallengeSchema = z.object({
  installationId: z.string().min(16).max(128).regex(/^[a-zA-Z0-9._-]+$/),
  buildSha: z.string().regex(/^[0-9a-fA-F]{40}$/)
});
const enrollmentSchema = z.object({
  nonce: z.string().min(32).max(128).regex(/^[A-Za-z0-9_-]+$/),
  integrityToken: z.string().min(100).max(256_000)
});
const devicePairingSchema = z.object({
  installationId: z.string().min(16).max(128).regex(/^[a-zA-Z0-9._-]+$/),
  buildSha: z.string().regex(/^[0-9a-fA-F]{40}$/),
  pairingCode: z.string().min(20).max(128)
});

app.get('/health', async (_req, res) => {
  try {
    const health = await backend.health();
    res.status(health.ok ? 200 : 503).json({
      service: 'veltrix-ultron-mcp',
      version: serviceVersion,
      releaseSha: releaseSha ?? null,
      ok: health.ok,
      detail: safeHealthDetail(health.ok)
    });
  } catch {
    res.status(503).json({
      service: 'veltrix-ultron-mcp',
      version: serviceVersion,
      releaseSha: releaseSha ?? null,
      ok: false,
      detail: 'unavailable'
    });
  }
});

app.get('/v1/bootstrap/config', (_req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  if (!secureBootstrap) return void res.status(503).json({ enrollmentEnabled: false });
  res.json({ enrollmentEnabled: true, ...secureBootstrap.publicConfig() });
});

app.post('/v1/device/enroll/challenge', async (req, res) => {
  if (!secureBootstrap) return void res.status(503).json({ error: 'secure_enrollment_not_configured' });
  try {
    bootstrapLimiter.assertAllowed(`challenge:${requestAbuseKey(req)}`);
    const parsed = enrollmentChallengeSchema.parse(req.body);
    res.setHeader('Cache-Control', 'no-store');
    res.json(await secureBootstrap.createChallenge(parsed));
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    res.status(400).json({ error: enrollmentError(error) });
  }
});

app.post('/v1/device/enroll', async (req, res) => {
  if (!secureBootstrap) return void res.status(503).json({ error: 'secure_enrollment_not_configured' });
  try {
    bootstrapLimiter.assertAllowed(`enroll:${requestAbuseKey(req)}`);
    const parsed = enrollmentSchema.parse(req.body);
    res.setHeader('Cache-Control', 'no-store');
    res.status(201).json(await secureBootstrap.enroll(parsed));
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    res.status(403).json({ error: enrollmentError(error) });
  }
});

app.post('/v1/device/pair', async (req, res) => {
  if (!secureBootstrap) return void res.status(503).json({ error: 'secure_enrollment_not_configured' });
  try {
    pairingLimiter.assertAllowed(`pair:${requestAbuseKey(req)}`);
    const parsed = devicePairingSchema.parse(req.body);
    res.setHeader('Cache-Control', 'no-store');
    res.status(201).json(await secureBootstrap.pairWithCode(parsed));
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    const message = error instanceof Error ? error.message : '';
    const code = /^(invalid_pairing_code|secure_pairing_not_configured|enrollment_build_sha_not_allowed|invalid_installation_id|invalid_build_sha)$/.test(message)
      ? message
      : 'pairing_failed';
    res.status(code === 'secure_pairing_not_configured' ? 503 : 403).json({ error: code });
  }
});

app.post('/v1/tasks', async (req, res) => {
  const binding = principalFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_token' });
  if (!binding.scopes.includes('ultron:tasks')) return void res.status(403).json({ error: 'insufficient_scope' });
  try {
    submissionLimiter.assertAllowed(binding.principalId);
    const parsed = submitTaskSchema.parse(req.body);
    const body: SubmitTaskInput = {
      objective: parsed.objective,
      target: parsed.target,
      requiredCapabilities: parsed.requiredCapabilities,
      constraints: parsed.constraints,
      ...(parsed.deviceId ? { deviceId: parsed.deviceId } : {}),
      ...(parsed.idempotencyKey ? { idempotencyKey: parsed.idempotencyKey } : {})
    };
    res.status(202).json(await backend.submitTask(binding, body));
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    if (error instanceof IdempotencyConflictError) {
      return void res.status(409).json({ error: 'idempotency_conflict' });
    }
    res.status(400).json({ error: safePublicErrorCode(error, 'task_submit_failed') });
  }
});

app.get('/v1/tasks/:taskId', async (req, res) => {
  const binding = principalFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_token' });
  if (!binding.scopes.includes('ultron:tasks')) return void res.status(403).json({ error: 'insufficient_scope' });
  try {
    const receipt = await backend.getTask(binding, String(req.params.taskId));
    if (!receipt) return void res.status(404).json({ error: 'not_found' });
    res.json(receipt);
  } catch (error) {
    res.status(403).json({ error: safePublicErrorCode(error, 'task_read_failed') });
  }
});

for (const operation of ['pause', 'resume', 'cancel'] as const) {
  app.post(`/v1/tasks/:taskId/${operation}`, async (req, res) => {
    const binding = principalFromBearer(req.headers.authorization);
    if (!binding) return void res.status(401).json({ error: 'invalid_token' });
    if (!binding.scopes.includes('ultron:tasks')) return void res.status(403).json({ error: 'insufficient_scope' });
    try {
      principalMutationLimiter.assertAllowed(binding.principalId);
      const taskId = String(req.params.taskId);
      const receipt = operation === 'pause'
        ? await backend.pauseTask(binding, taskId)
        : operation === 'resume'
          ? await backend.resumeTask(binding, taskId)
          : await backend.cancelTask(binding, taskId);
      res.json(receipt);
    } catch (error) {
      if (respondRateLimited(res, error)) return;
      res.status(409).json({ error: safePublicErrorCode(error, 'task_mutation_failed') });
    }
  });
}

app.get('/v1/devices', async (req, res) => {
  const binding = principalFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_token' });
  if (!binding.scopes.includes('ultron:devices')) return void res.status(403).json({ error: 'insufficient_scope' });
  try {
    res.json(await backend.listDevices(binding));
  } catch (error) {
    res.status(503).json({ error: safePublicErrorCode(error, 'device_list_failed') });
  }
});

app.get('/v1/devices/:deviceId', async (req, res) => {
  const binding = principalFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_token' });
  if (!binding.scopes.includes('ultron:devices')) return void res.status(403).json({ error: 'insufficient_scope' });
  try {
    const device = await backend.getDevice(binding, String(req.params.deviceId));
    if (!device) return void res.status(404).json({ error: 'not_found_or_denied' });
    res.json(device);
  } catch (error) {
    res.status(503).json({ error: safePublicErrorCode(error, 'device_read_failed') });
  }
});

app.post('/v1/devices/:deviceId/control-requests', async (req, res) => {
  const binding = principalFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_token' });
  if (!binding.scopes.includes('ultron:control-request')) return void res.status(403).json({ error: 'insufficient_scope' });
  try {
    principalMutationLimiter.assertAllowed(binding.principalId);
    const body = controlSchema.parse(req.body);
    res.json(await backend.requestControl(binding, String(req.params.deviceId), body.profile, body.reason));
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    res.status(400).json({ error: safePublicErrorCode(error, 'control_request_failed') });
  }
});

app.get('/v1/capabilities', async (_req, res) => {
  try {
    res.json(await backend.capabilities());
  } catch {
    res.status(503).json({ error: 'capabilities_unavailable' });
  }
});

app.post('/v1/device/live/token', async (req, res) => {
  const binding = deviceFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
  if (!binding.allowedCapabilities.includes('MICROPHONE_CAPTURE')) {
    return void res.status(403).json({ error: 'microphone_capability_not_granted' });
  }
  if (!geminiLiveTokenIssuer) {
    return void res.status(503).json({ error: 'gemini_live_not_configured' });
  }
  try {
    liveTokenLimiter.assertAllowed(binding.deviceId);
    res.setHeader('Cache-Control', 'no-store');
    res.json(await geminiLiveTokenIssuer.issue());
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    res.status(502).json({ error: safePublicErrorCode(error, 'live_token_unavailable') });
  }
});

app.get('/v1/device/planner/status', (req, res) => {
  const binding = deviceFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
  res.setHeader('Cache-Control', 'no-store');
  res.json(backendAiPlanner.status());
});

app.post('/v1/device/planner/plan', async (req, res) => {
  const binding = deviceFromBearer(req.headers.authorization);
  if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
  try {
    const scoped = scopeDevicePlannerRequest(binding, req.body);
    aiPlanLimiter.assertAllowed(binding.deviceId);
    res.setHeader('Cache-Control', 'no-store');
    res.json(await backendAiPlanner.plan(scoped));
  } catch (error) {
    if (respondRateLimited(res, error)) return;
    if (error instanceof DevicePlannerBindingError) {
      return void res.status(403).json({ error: error.safeCode });
    }
    if (error instanceof z.ZodError) {
      return void res.status(400).json({ error: 'invalid_planner_request' });
    }
    res.status(400).json({ error: 'planner_request_failed' });
  }
});

if (deviceBridge) {
  app.post('/v1/device/heartbeat', async (req, res) => {
    const binding = deviceFromBearer(req.headers.authorization);
    if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
    try {
      deviceMutationLimiter.assertAllowed(binding.deviceId);
      res.json(await deviceBridge.deviceHeartbeat(binding, heartbeatSchema.parse(req.body)));
    } catch (error) {
      if (respondRateLimited(res, error)) return;
      res.status(400).json({ error: safePublicErrorCode(error, 'device_heartbeat_failed') });
    }
  });

  app.post('/v1/device/tasks/claim', async (req, res) => {
    const binding = deviceFromBearer(req.headers.authorization);
    if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
    try {
      deviceMutationLimiter.assertAllowed(binding.deviceId);
      res.json({ task: (await deviceBridge.deviceClaimNext(binding)) ?? null });
    } catch (error) {
      if (respondRateLimited(res, error)) return;
      res.status(409).json({ error: safePublicErrorCode(error, 'device_claim_failed') });
    }
  });

  app.post('/v1/device/tasks/wait', async (req, res) => {
    const binding = deviceFromBearer(req.headers.authorization);
    if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
    try {
      devicePollLimiter.assertAllowed(binding.deviceId);
      const { timeoutMs } = waitSchema.parse(req.body ?? {});
      res.setHeader('Cache-Control', 'no-store');
      res.json({ task: await waitForDeviceTask(deviceBridge, binding, timeoutMs) });
    } catch (error) {
      if (respondRateLimited(res, error)) return;
      res.status(409).json({ error: safePublicErrorCode(error, 'device_wait_failed') });
    }
  });

  app.get('/v1/device/tasks/:taskId', async (req, res) => {
    const binding = deviceFromBearer(req.headers.authorization);
    if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
    try {
      res.json(await deviceBridge.deviceInspectTask(binding, String(req.params.taskId)));
    } catch (error) {
      res.status(404).json({ error: safePublicErrorCode(error, 'device_task_not_found') });
    }
  });

  app.post('/v1/device/tasks/:taskId/state', async (req, res) => {
    const binding = deviceFromBearer(req.headers.authorization);
    if (!binding) return void res.status(401).json({ error: 'invalid_device_token' });
    try {
      deviceMutationLimiter.assertAllowed(binding.deviceId);
      const parsed = deviceUpdateSchema.parse(req.body);
      const update: DeviceTaskUpdate = {
        state: parsed.state,
        narration: parsed.narration,
        ...(parsed.evidence === undefined ? {} : { evidence: parsed.evidence })
      };
      res.json(await deviceBridge.deviceUpdateTask(binding, String(req.params.taskId), update));
    } catch (error) {
      if (respondRateLimited(res, error)) return;
      res.status(409).json({ error: safePublicErrorCode(error, 'device_state_update_failed') });
    }
  });
}

app.all('/mcp', auth, (req, res) => void nodeHandler(req, res, req.body));

const server = app.listen(port, host, () => {
  console.error(`[ultron-mcp] listening on http://${host}:${port}/mcp`);
});

function createBackend(): UltronBackend {
  const mode = (process.env.ULTRON_BACKEND ?? '').trim().toLowerCase();
  if (mode !== 'bridge') return backendFromEnv();

  const defaultStore = process.env.NODE_ENV === 'production' ? 'postgres' : 'memory';
  const store = (process.env.ULTRON_BRIDGE_STORE ?? defaultStore).trim().toLowerCase();
  if (store === 'postgres') {
    return new PostgresBridgeUltronBackend({
      connectionString: process.env.DATABASE_URL?.trim() ?? ''
    });
  }
  if (store === 'memory') {
    if (process.env.NODE_ENV === 'production' && process.env.ULTRON_ALLOW_IN_MEMORY_BRIDGE !== 'true') {
      throw new Error('Production bridge requires Postgres; set DATABASE_URL or explicitly allow unsafe in-memory bridge');
    }
    return new BridgeUltronBackend();
  }
  throw new Error(`Unsupported ULTRON_BRIDGE_STORE: ${store}`);
}

async function waitForDeviceTask(
  bridge: DeviceBridgeBackend,
  binding: DeviceBinding,
  timeoutMs: number
) {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const task = await bridge.deviceClaimNext(binding);
    if (task) return task;
    const remaining = deadline - Date.now();
    if (remaining <= 0) return null;
    await sleep(Math.min(500, remaining));
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function principalFromBearer(value: string | undefined): PrincipalBinding | undefined {
  const token = bearerToken(value);
  return token ? registry.resolveToken(token) : undefined;
}

function deviceFromBearer(value: string | undefined): DeviceBinding | undefined {
  const token = bearerToken(value);
  return token ? deviceRegistry.resolveToken(token) : undefined;
}

function bearerToken(value: string | undefined): string | undefined {
  if (!value) return undefined;
  const match = /^Bearer\s+(.+)$/i.exec(value.trim());
  return match?.[1]?.trim() || undefined;
}

function requestAbuseKey(req: {
  ip?: string | undefined;
  socket?: { remoteAddress?: string | undefined } | undefined;
}): string {
  return req.ip?.trim() || req.socket?.remoteAddress?.trim() || 'unknown';
}

function respondRateLimited(
  res: { setHeader(name: string, value: string): unknown; status(code: number): { json(body: unknown): unknown } },
  error: unknown
): boolean {
  if (!(error instanceof SubmissionRateLimitError)) return false;
  res.setHeader('Retry-After', String(error.retryAfterSeconds));
  res.status(429).json({ error: 'rate_limited' });
  return true;
}

function shutdown(signal: string): void {
  console.error(`[ultron-mcp] ${signal}; shutting down`);
  server.close(error => {
    void closeBackend().finally(() => {
      if (error) {
        console.error('[ultron-mcp] shutdown error');
        process.exitCode = 1;
      }
    });
  });
}

async function closeBackend(): Promise<void> {
  await secureBootstrap?.close();
  if (backend instanceof PostgresBridgeUltronBackend) await backend.close();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

function splitCsv(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}

function parsePort(value: string): number {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) throw new Error('Invalid MCP port');
  return parsed;
}

function parseReleaseSha(value: string | undefined): string | undefined {
  const normalized = value?.trim().toLowerCase();
  if (!normalized) return undefined;
  if (!/^[0-9a-f]{40}$/.test(normalized)) {
    throw new Error('ULTRON release SHA must be a 40-character hexadecimal Git commit SHA');
  }
  return normalized;
}

function enrollmentError(error: unknown): string {
  const message = error instanceof Error ? error.message : 'enrollment_failed';
  if (/^(integrity_|enrollment_|invalid_|secure_enrollment_)/.test(message)) return message;
  return 'enrollment_failed';
}
