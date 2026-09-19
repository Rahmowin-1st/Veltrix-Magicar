import assert from 'node:assert/strict';

import { AiBrainRouter } from './ai-brain-router.js';
import { aiProvidersFromEnv } from './ai-planner.js';

const secrets = {
  ULTRON_AI_GEMINI_LIVE_API_KEY: 'gemini-live-secret',
  ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-brain-secret',
  ULTRON_AI_GROQ_API_KEY: 'groq-secret'
};

const providers = aiProvidersFromEnv(secrets);
const router = new AiBrainRouter(providers, secrets);

{
  const route = router.route('MAIN_ASSISTANT');
  assert.deepEqual(route.map(candidate => candidate.id), ['gemini.brain', 'groq.primary']);
  assert.equal(route[0]?.modelId, 'gemini-3.8-flash');
}
{
  const route = router.route('PLANNER');
  assert.equal(route[0]?.id, 'gemini.brain');
}
{
  const route = router.route('VISION');
  assert.deepEqual(route.map(candidate => candidate.id), ['gemini.brain']);
  assert.ok(route[0]?.supportsVision);
}
{
  const fast = router.route('FAST_WORKER');
  const memory = router.route('MEMORY_SEARCHER');
  assert.deepEqual(fast.map(candidate => candidate.id), ['groq.primary', 'gemini.brain']);
  assert.equal(fast[0]?.modelId, 'openai/gpt-oss-20b');
  assert.deepEqual(memory.map(candidate => candidate.id), fast.map(candidate => candidate.id));
}
{
  const route = router.route('DEEP_VERIFIER');
  assert.deepEqual(route.map(candidate => candidate.id), ['groq.primary', 'gemini.brain']);
  assert.equal(route[0]?.modelId, 'openai/gpt-oss-120b');
}
{
  const custom = new AiBrainRouter(providers, {
    ...secrets,
    ULTRON_AI_GROQ_DEEP_MODEL: 'openai/gpt-oss-120b-custom'
  });
  assert.equal(custom.route('DEEP_VERIFIER')[0]?.modelId, 'openai/gpt-oss-120b-custom');
}
{
  const status = router.status();
  assert.equal(status.enabled, true);
  assert.equal(status.providerCount, 2);
  assert.equal(status.familyCount, 2);
  assert.equal(status.roles.MAIN_ASSISTANT.primaryFamily, 'gemini');
  assert.equal(status.roles.FAST_WORKER.primaryFamily, 'groq');
  assert.equal(status.roles.MEMORY_SEARCHER.primaryFamily, 'groq');
  assert.equal(status.roles.DEEP_VERIFIER.primaryFamily, 'groq');
  assert.equal(status.roles.VISION.primaryFamily, 'gemini');
  const serialized = JSON.stringify(status);
  for (const secret of Object.values(secrets)) {
    assert.equal(serialized.includes(secret), false, 'status must never expose provider credentials');
  }
}
{
  const noGroq = new AiBrainRouter(aiProvidersFromEnv({
    ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-only'
  }));
  assert.equal(noGroq.status().roles.MAIN_ASSISTANT.ready, true);
  assert.equal(noGroq.status().roles.FAST_WORKER.ready, true);
  assert.equal(noGroq.status().roles.DEEP_VERIFIER.ready, true, 'deep verifier may degrade to Gemini when Groq is unavailable');
}
console.log('Car V1 role-aware AI brain router self-test PASS');
