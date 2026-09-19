import assert from 'node:assert/strict';

import {
  BackendAiPlanner,
  aiProvidersFromEnv,
  type BackendAiProviderConfig,
  type BackendPlannerRequest,
  type PlannerFetch
} from './ai-planner.js';
import type { DeviceBinding } from './device-auth.js';
import {
  DevicePlannerBindingError,
  parseAiPlanRateLimit,
  scopeDevicePlannerRequest
} from './device-planner.js';

const validRequest: BackendPlannerRequest = {
  objective: 'Open the example app',
  constraints: ['Do not bypass user permission'],
  device: {
    id: 'android-local',
    kind: 'PHONE',
    platform: 'ANDROID',
    effectiveCapabilities: ['SCREEN_OBSERVE', 'OPEN_APP']
  },
  observation: {
    foregroundApp: 'com.veltrix.ultron',
    foregroundWindow: 'Veltrix',
    uri: 'https://example.com/path?secret=query#private',
    visibleText: ['Normal visible text']
  },
  memoryHints: ['Prefer the example app'],
  recentEvidence: [],
  failedSteps: [],
  vision: null,
  previousSteps: [],
  replanReason: null
};

const validProposal = {
  objective: 'Open the example app',
  narration: 'I will open the app and verify it.',
  confidence: 0.95,
  explanation: 'One bounded step.',
  steps: [
    {
      id: 'step-1',
      description: 'Open the example app',
      action: {
        type: 'OPEN_APP',
        target: 'com.example.app',
        text: null,
        value: null,
        x: null,
        y: null,
        metadata: {}
      },
      required_capability: 'OPEN_APP',
      target_scope: 'com.example.app',
      risk: 'LOW',
      verification: {
        mode: 'APP',
        expected: 'com.example.app',
        description: 'The example app is foreground.'
      },
      max_attempts: 2
    }
  ]
};

function provider(
  id: string,
  priority: number,
  supportsVision = false,
  apiKey = `key-${id}-not-for-prompt`
): BackendAiProviderConfig {
  return {
    id,
    baseUrl: `https://${id}.example.com/v1`,
    modelId: `model-${id}`,
    apiKey,
    priority,
    supportsVision,
    supportsJsonObject: true
  };
}

function providerResponse(proposal: unknown = validProposal, status = 200): Response {
  return new Response(
    JSON.stringify({
      choices: [{ message: { content: JSON.stringify(proposal) } }]
    }),
    {
      status,
      headers: { 'Content-Type': 'application/json' }
    }
  );
}

function sequenceFetch(
  handlers: Array<(url: string, init: RequestInit) => Response | Promise<Response>>
): PlannerFetch {
  let index = 0;
  return async (input, init) => {
    const handler = handlers[index++];
    assert.ok(handler, `unexpected provider request #${index}`);
    return handler(String(input), init ?? {});
  };
}

{
  const planner = new BackendAiPlanner([], async () => {
    throw new Error('provider must not be contacted when none are configured');
  });
  const result = await planner.plan(validRequest);
  assert.equal(result.state, 'REJECTED');
  if (result.state === 'REJECTED') assert.equal(result.code, 'AI_NOT_CONFIGURED');
}

{
  const env: NodeJS.ProcessEnv = {
    ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-secret',
    ULTRON_AI_GROQ_API_KEY: 'groq-secret'
  };
  const providers = aiProvidersFromEnv(env);
  assert.deepEqual(providers.map(item => item.id), ['gemini.brain', 'groq.primary']);
  assert.equal(providers[0]?.baseUrl, 'https://generativelanguage.googleapis.com/v1beta/openai');
  assert.equal(providers[0]?.modelId, 'gemini-3.8-flash');
  assert.equal(providers[1]?.baseUrl, 'https://api.groq.com/openai/v1');
  assert.equal(providers[1]?.modelId, 'openai/gpt-oss-20b');
}

{
  const providers = aiProvidersFromEnv({
    ULTRON_AI_GEMINI_LIVE_API_KEY: 'live-key-is-not-a-planner-key',
    ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-brain',
    ULTRON_AI_GROQ_API_KEY: 'groq-fast'
  });
  assert.deepEqual(
    providers.map(item => item.id),
    ['gemini.brain', 'groq.primary'],
    'car cognitive routing must expose exactly Gemini Brain plus Groq; Gemini Live stays isolated'
  );
}

{
  const providers = aiProvidersFromEnv({
    ULTRON_AI_GEMINI_BRAIN_API_KEY: 'same-secret',
    ULTRON_AI_GROQ_API_KEY: 'same-secret'
  });
  assert.deepEqual(
    providers.map(item => item.id),
    ['gemini.brain', 'groq.primary'],
    'the same opaque secret string in different provider families remains independently scoped'
  );
}

{
  const providers = aiProvidersFromEnv({
    ULTRON_AI_CEREBRAS_API_KEY: 'old-cerebras',
    ULTRON_AI_NVIDIA_API_KEY: 'old-nvidia',
    ULTRON_AI_NVIDIA_MODEL: 'old-model',
    ULTRON_AI_9ROUTER_API_KEY: 'old-router',
    ULTRON_AI_9ROUTER_BASE_URL: 'https://router.example.com/v1',
    ULTRON_AI_9ROUTER_MODEL: 'old-model'
  });
  assert.deepEqual(providers, [], 'retired non-canonical provider variables must not activate AI routing');
}

assert.throws(
  () => aiProvidersFromEnv({
    ULTRON_AI_GEMINI_BRAIN_API_KEY: 'secret',
    ULTRON_AI_GEMINI_BASE_URL: 'https://127.0.0.1/v1'
  }),
  /host is not allowed/
);

{
  const binding: DeviceBinding = {
    deviceId: 'android-local',
    ownerPrincipalId: 'owner',
    kind: 'PHONE',
    displayName: 'Bound Android',
    allowedCapabilities: ['OPEN_APP'],
    controlProfile: 'ASK_EACH_ACTION'
  };
  const scoped = scopeDevicePlannerRequest(binding, {
    ...validRequest,
    device: {
      ...validRequest.device,
      effectiveCapabilities: ['OPEN_APP', 'UI_TYPE']
    }
  });
  assert.equal(scoped.device.id, binding.deviceId);
  assert.equal(scoped.device.kind, binding.kind);
  assert.deepEqual(
    scoped.device.effectiveCapabilities,
    ['OPEN_APP'],
    'model-visible capability set must be intersected with device bearer scope'
  );

  assert.throws(
    () => scopeDevicePlannerRequest(binding, {
      ...validRequest,
      device: { ...validRequest.device, id: 'other-device' }
    }),
    error => error instanceof DevicePlannerBindingError && error.safeCode === 'device_binding_mismatch'
  );
}

assert.equal(parseAiPlanRateLimit(undefined), 30);
assert.equal(parseAiPlanRateLimit('12'), 12);
assert.throws(() => parseAiPlanRateLimit('0'), /ULTRON_AI_PLAN_RATE_PER_MINUTE/);
assert.throws(() => parseAiPlanRateLimit('601'), /ULTRON_AI_PLAN_RATE_PER_MINUTE/);

{
  const calls: string[] = [];
  const planner = new BackendAiPlanner(
    [provider('first', 10), provider('second', 20)],
    sequenceFetch([
      (url) => {
        calls.push(url);
        return new Response('', { status: 500 });
      },
      (url) => {
        calls.push(url);
        return providerResponse();
      }
    ])
  );
  const result = await planner.plan(validRequest);
  assert.equal(result.state, 'PROPOSED');
  if (result.state === 'PROPOSED') assert.equal(result.providerId, 'second');
  assert.equal(calls.length, 2, 'retryable provider failure must fall back');
}

{
  const calls: string[] = [];
  const planner = new BackendAiPlanner(
    [provider('gemini.brain', 10), provider('groq.primary', 20)],
    sequenceFetch([
      (url) => {
        calls.push(url);
        assert.match(url, /gemini\.brain/);
        return new Response('', { status: 429 });
      },
      (url) => {
        calls.push(url);
        assert.match(url, /groq\.primary/);
        return providerResponse();
      }
    ])
  );
  const result = await planner.plan(validRequest);
  assert.equal(result.state, 'PROPOSED');
  if (result.state === 'PROPOSED') assert.equal(result.providerId, 'groq.primary');
  assert.equal(calls.length, 2, '429 must skip same-family sibling keys instead of cycling quota credentials');
}

{
  const planner = new BackendAiPlanner(
    [provider('gemini.brain', 10), provider('groq.primary', 20)],
    sequenceFetch([
      () => new Response('', { status: 401 }),
      () => providerResponse()
    ])
  );
  const result = await planner.plan(validRequest);
  assert.equal(result.state, 'PROPOSED');
  if (result.state === 'PROPOSED') {
    assert.equal(result.providerId, 'groq.primary', 'credential-specific failure falls through to the other configured provider');
  }
}

{
  const invalidProposal = {
    ...validProposal,
    steps: [{
      ...validProposal.steps[0],
      action: { ...validProposal.steps[0]?.action, type: 'RAW_SHELL' }
    }]
  };
  const planner = new BackendAiPlanner(
    [provider('invalid', 10), provider('valid', 20)],
    sequenceFetch([
      () => providerResponse(invalidProposal),
      () => providerResponse()
    ])
  );
  const result = await planner.plan(validRequest);
  assert.equal(result.state, 'PROPOSED');
  if (result.state === 'PROPOSED') assert.equal(result.providerId, 'valid');
}

{
  let contacted = false;
  const visionRequest: BackendPlannerRequest = {
    ...validRequest,
    vision: {
      mimeType: 'image/jpeg',
      sourcePackage: 'com.example.app',
      capturedAtEpochMs: Date.now(),
      base64: Buffer.from('bounded-image').toString('base64')
    }
  };
  const planner = new BackendAiPlanner(
    [provider('text-only', 10, false)],
    async () => {
      contacted = true;
      return providerResponse();
    }
  );
  const result = await planner.plan(visionRequest);
  assert.equal(result.state, 'REJECTED');
  if (result.state === 'REJECTED') assert.equal(result.code, 'NO_VISION_PROVIDER');
  assert.equal(contacted, false, 'vision must never be sent to a text-only provider');
}

{
  const apiKey = 'provider-secret-must-stay-header-only';
  const rawBearer = 'Bearer abcdefghijklmnopqrstuvwxyz123456';
  const rawApiKey = 'sk-super-secret-provider-key-12345';
  const rawNumber = '4242 4242 4242 4242';
  const rawOtp = '654321';
  let capturedBody = '';
  let capturedAuthorization = '';
  const planner = new BackendAiPlanner(
    [provider('redaction', 10, true, apiKey)],
    async (_input, init) => {
      capturedBody = String(init?.body ?? '');
      capturedAuthorization = new Headers(init?.headers).get('authorization') ?? '';
      return providerResponse();
    }
  );
  const result = await planner.plan({
    ...validRequest,
    observation: {
      ...validRequest.observation,
      uri: 'https://example.com/path?token=secret-value#private',
      visibleText: [rawBearer, rawApiKey, rawNumber, rawOtp]
    },
    memoryHints: [`memory ${rawBearer}`]
  });
  assert.equal(result.state, 'PROPOSED');
  assert.equal(capturedAuthorization, `Bearer ${apiKey}`);
  assert.ok(!capturedBody.includes(apiKey), 'provider key must not appear in provider request JSON');
  assert.ok(!capturedBody.includes(rawBearer), 'bearer tokens must be redacted from prompt data');
  assert.ok(!capturedBody.includes(rawApiKey), 'API-key-like text must be redacted from prompt data');
  assert.ok(!capturedBody.includes(rawNumber), 'card-like numbers must be redacted from prompt data');
  assert.ok(!capturedBody.includes(rawOtp), 'six-digit codes must be redacted from prompt data');
  assert.ok(!capturedBody.includes('token=secret-value'), 'URL query values must not reach provider prompt');
  assert.ok(capturedBody.includes('<redacted-token>'));
  assert.ok(capturedBody.includes('<redacted-api-key>'));
}

{
  const oversized = 'x'.repeat(1_000_100);
  const planner = new BackendAiPlanner(
    [provider('oversized', 10)],
    async () => new Response(oversized, { status: 200 })
  );
  const result = await planner.plan(validRequest);
  assert.equal(result.state, 'REJECTED');
  if (result.state === 'REJECTED') {
    assert.equal(result.code, 'PROVIDER_FALLBACKS_EXHAUSTED');
    assert.match(result.message, /PROVIDER_RESPONSE_TOO_LARGE/);
    assert.ok(!result.message.includes(oversized.slice(0, 100)), 'provider body must not leak into safe error');
  }
}

{
  const tooLargeVision: BackendPlannerRequest = {
    ...validRequest,
    vision: {
      mimeType: 'image/jpeg',
      sourcePackage: 'com.example.app',
      capturedAtEpochMs: Date.now(),
      base64: Buffer.alloc(225_001, 1).toString('base64')
    }
  };
  const result = await new BackendAiPlanner(
    [provider('vision', 10, true)],
    async () => providerResponse()
  ).plan(tooLargeVision);
  assert.equal(result.state, 'REJECTED');
  if (result.state === 'REJECTED') assert.equal(result.code, 'VISION_SIZE_INVALID');
}

console.error('[ultron-mcp] backend AI planner self-test PASS');
