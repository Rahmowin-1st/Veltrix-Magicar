import assert from 'node:assert/strict';

import { AiBrainRuntime } from './ai-brain-runtime.js';
import { aiProvidersFromEnv, type PlannerFetch } from './ai-planner.js';

const env = {
  ULTRON_AI_GEMINI_LIVE_API_KEY: 'gemini-live-secret',
  ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-brain-secret',
  ULTRON_AI_GROQ_API_KEY: 'groq-secret'
};
const providers = aiProvidersFromEnv(env);

function providerResponse(content = 'ok'): Response {
  return new Response(JSON.stringify({ choices: [{ message: { content } }] }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  });
}

function authorization(init?: RequestInit): string {
  return new Headers(init?.headers).get('authorization') ?? '';
}

{
  const calls: Array<{ authorization: string; body: string }> = [];
  const runtime = new AiBrainRuntime(providers, async (_input, init) => {
    calls.push({ authorization: authorization(init), body: String(init?.body ?? '') });
    return providerResponse('main-answer');
  }, env);
  const result = await runtime.run({
    role: 'MAIN_ASSISTANT',
    instruction: 'Help with this goal',
    input: 'Open settings'
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') {
    assert.equal(result.providerId, 'gemini.brain');
    assert.equal(result.modelId, 'gemini-3.8-flash');
    assert.equal(result.content, 'main-answer');
  }
  assert.equal(calls[0]?.authorization, 'Bearer gemini-brain-secret');
}

{
  const calls: string[] = [];
  const runtime = new AiBrainRuntime(providers, async (_input, init) => {
    const auth = authorization(init);
    calls.push(auth);
    if (auth === 'Bearer groq-secret') return new Response('', { status: 429 });
    return providerResponse('fallback-ok');
  }, env);
  const result = await runtime.run({
    role: 'FAST_WORKER',
    instruction: 'Classify',
    input: 'normal input'
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') assert.equal(result.providerId, 'gemini.brain');
  assert.deepEqual(
    calls,
    ['Bearer groq-secret', 'Bearer gemini-brain-secret'],
    '429 must skip same-family reliability siblings instead of cycling quota keys'
  );
}

{
  const calls: string[] = [];
  const runtime = new AiBrainRuntime(providers, async (_input, init) => {
    const auth = authorization(init);
    calls.push(auth);
    if (auth === 'Bearer groq-secret') return new Response('', { status: 401 });
    return providerResponse('rotated-key-ok');
  }, env);
  const result = await runtime.run({
    role: 'MEMORY_SEARCHER',
    instruction: 'Rerank already-authorized candidates',
    input: 'candidate metadata'
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') assert.equal(result.providerId, 'gemini.brain');
  assert.deepEqual(calls.slice(0, 2), ['Bearer groq-secret', 'Bearer gemini-brain-secret']);
}

{
  let requestBody = '';
  const runtime = new AiBrainRuntime(providers, async (_input, init) => {
    requestBody = String(init?.body ?? '');
    return providerResponse('{"verdict":"needs_more_evidence"}');
  }, env);
  const result = await runtime.run({
    role: 'DEEP_VERIFIER',
    instruction: 'Verify independently',
    input: 'claimed result',
    responseMode: 'JSON_OBJECT'
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') {
    assert.equal(result.providerId, 'groq.primary');
    assert.equal(result.modelId, 'openai/gpt-oss-120b');
    assert.equal(result.content, '{"verdict":"needs_more_evidence"}');
  }
  assert.equal(JSON.parse(requestBody).model, 'openai/gpt-oss-120b');
  assert.deepEqual(JSON.parse(requestBody).response_format, { type: 'json_object' });
}

{
  let body = '';
  const runtime = new AiBrainRuntime(providers, async (_input, init) => {
    body = String(init?.body ?? '');
    return providerResponse('safe');
  }, env);
  await runtime.run({
    role: 'FAST_WORKER',
    instruction: 'Extract metadata',
    input: 'bearer abcdefghijklmnopqrstuvwxyz sk-supersecretkey123 password=hunter2 123456 {"password":"json-hunter2","api_key":"json-private-key"} {\\"password\\":\\"escaped-hunter2\\",\\"api_key\\":\\"escaped-private-key\\"}',
    context: ['api_key=private-key-material', "{'secret':'quoted-private-secret'}", '{\\"token\\":\\"escaped-token-material\\"}']
  });
  assert.equal(body.includes('abcdefghijklmnopqrstuvwxyz'), false);
  assert.equal(body.includes('sk-supersecretkey123'), false);
  assert.equal(body.includes('hunter2'), false);
  assert.equal(body.includes('private-key-material'), false);
  assert.equal(body.includes('json-private-key'), false);
  assert.equal(body.includes('quoted-private-secret'), false);
  assert.equal(body.includes('escaped-hunter2'), false);
  assert.equal(body.includes('escaped-private-key'), false);
  assert.equal(body.includes('escaped-token-material'), false);
  assert.match(body, /redacted/i);
}

{
  let body = '';
  const runtime = new AiBrainRuntime(providers, async (_input, init) => {
    body = String(init?.body ?? '');
    return providerResponse('vision-ok');
  }, env);
  const base64 = Buffer.from('bounded-vision-payload', 'utf8').toString('base64');
  const result = await runtime.run({
    role: 'VISION',
    instruction: 'Describe only the supplied image',
    input: 'User-consented screenshot',
    vision: { mimeType: 'image/png', base64 }
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') assert.equal(result.providerId, 'gemini.brain');
  const parsed = JSON.parse(body) as { messages: Array<{ content: unknown }> };
  const content = parsed.messages[1]?.content;
  assert.ok(Array.isArray(content), 'VISION provider request must carry multimodal content');
  const image = (content as Array<{ type?: string; image_url?: { url?: string } }>).find(item => item.type === 'image_url');
  assert.equal(image?.image_url?.url, `data:image/png;base64,${base64}`);
}

{
  const runtime = new AiBrainRuntime(providers, async () => providerResponse('unused'), env);
  const missingVision = await runtime.run({
    role: 'VISION',
    instruction: 'Inspect image',
    input: 'No image supplied'
  });
  assert.deepEqual(missingVision, {
    state: 'REJECTED',
    role: 'VISION',
    code: 'VISION_INPUT_REQUIRED',
    retryable: false
  });

  const wrongRole = await runtime.run({
    role: 'FAST_WORKER',
    instruction: 'Classify',
    input: 'text',
    vision: { mimeType: 'image/png', base64: Buffer.from('x').toString('base64') }
  });
  assert.deepEqual(wrongRole, {
    state: 'REJECTED',
    role: 'FAST_WORKER',
    code: 'VISION_ROLE_REQUIRED',
    retryable: false
  });
}

{
  const runtime = new AiBrainRuntime(
    aiProvidersFromEnv({ ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-only' }),
    (async () => providerResponse('degraded-verifier')) as PlannerFetch
  );
  const result = await runtime.run({
    role: 'DEEP_VERIFIER',
    instruction: 'Verify',
    input: 'claim'
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') {
    assert.equal(result.providerId, 'gemini.brain');
    assert.equal(result.content, 'degraded-verifier');
  }
}

{
  const runtime = new AiBrainRuntime(providers, async () => providerResponse('```json\n{"kind":"route"}\n```'), env);
  const result = await runtime.run({
    role: 'FAST_WORKER',
    instruction: 'Return route JSON',
    input: 'task',
    responseMode: 'JSON_OBJECT'
  });
  assert.equal(result.state, 'ANSWERED');
  if (result.state === 'ANSWERED') assert.equal(result.content, '{"kind":"route"}');
}

{
  const runtime = new AiBrainRuntime(providers, async () => providerResponse('ok'), env);
  const invalid = await runtime.run({ role: 'ROOT', instruction: 'x', input: 'y' });
  assert.equal(invalid.state, 'REJECTED');
  if (invalid.state === 'REJECTED') assert.equal(invalid.code, 'INVALID_BRAIN_REQUEST');

  const serialized = JSON.stringify(runtime.status());
  for (const secret of Object.values(env)) {
    assert.equal(serialized.includes(secret), false, 'runtime status must not expose credentials');
  }
}

console.log('V2.52 bounded multi-role AI brain runtime self-test PASS');
