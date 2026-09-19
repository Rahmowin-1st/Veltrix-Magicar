import assert from 'node:assert/strict';

import { BackendAiPlanner } from './ai-planner.js';

const canonicalEnv: NodeJS.ProcessEnv = {
  ULTRON_AI_GEMINI_LIVE_API_KEY: 'gemini-live-secret',
  ULTRON_AI_GEMINI_BRAIN_API_KEY: 'gemini-brain-secret',
  ULTRON_AI_GROQ_API_KEY: 'groq-secret'
};

const status = BackendAiPlanner.fromEnv(canonicalEnv).status();
assert.deepEqual(status, {
  enabled: true,
  providerCount: 2,
  visionProviderCount: 1
});
const rendered = JSON.stringify(status);
for (const secret of Object.values(canonicalEnv)) {
  assert.equal(rendered.includes(secret ?? ''), false, 'readiness status must never expose provider secret values');
}
const emptyStatus = BackendAiPlanner.fromEnv({}).status();
assert.deepEqual(emptyStatus, { enabled: false, providerCount: 0, visionProviderCount: 0 });
console.error('[ultron-mcp] Car V1 AI readiness self-test PASS');
