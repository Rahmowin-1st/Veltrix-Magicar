import assert from 'node:assert/strict';

import {
  SubmissionRateLimitError,
  SubmissionRateLimiter,
  parseBoundedRateLimit,
  parseSubmissionRateLimit
} from './submission-rate-limit.js';

let now = 10_000;
const limiter = new SubmissionRateLimiter(2, 1_000, 2, () => now);
limiter.assertAllowed('principal-a');
limiter.assertAllowed('principal-a');
assert.throws(
  () => limiter.assertAllowed('principal-a'),
  (error: unknown) => error instanceof SubmissionRateLimitError && error.retryAfterSeconds === 1,
  'third submission inside the same window must be rejected with bounded retry guidance'
);

now += 1_000;
assert.doesNotThrow(() => limiter.assertAllowed('principal-a'), 'expired window must admit a new submission');
assert.doesNotThrow(() => limiter.assertAllowed('principal-b'));
assert.doesNotThrow(() => limiter.assertAllowed('principal-c'), 'bucket storage must evict instead of growing without bound');

assert.equal(parseSubmissionRateLimit(undefined), 60);
assert.equal(parseSubmissionRateLimit(' 7 '), 7);
assert.throws(() => parseSubmissionRateLimit('0'), /integer from 1 to 10000/);
assert.throws(() => parseSubmissionRateLimit('not-a-number'), /integer from 1 to 10000/);

assert.equal(parseBoundedRateLimit(undefined, 'ULTRON_ASSISTANT_RATE_PER_MINUTE', 30), 30);
assert.equal(parseBoundedRateLimit(' 12 ', 'ULTRON_ASSISTANT_RATE_PER_MINUTE', 30), 12);
assert.throws(
  () => parseBoundedRateLimit('0', 'ULTRON_ASSISTANT_RATE_PER_MINUTE', 30),
  /ULTRON_ASSISTANT_RATE_PER_MINUTE must be an integer from 1 to 10000/
);

console.error('[ultron-mcp] submission rate limit self-test PASS');
