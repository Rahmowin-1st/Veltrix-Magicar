import assert from 'node:assert/strict';

import { IdempotencyConflictError } from './idempotency.js';
import { safeHealthDetail, safePublicErrorCode } from './safe-errors.js';
import { SubmissionRateLimitError } from './submission-rate-limit.js';

assert.equal(safePublicErrorCode(new SubmissionRateLimitError(3)), 'rate_limited');
assert.equal(safePublicErrorCode(new IdempotencyConflictError()), 'idempotency_conflict');
assert.equal(safePublicErrorCode(new Error('Task not found')), 'not_found');
assert.equal(safePublicErrorCode(new Error('VERIFIED_DONE requires evidence')), 'evidence_required');

const sensitive = 'postgres://user:super-secret@db.example/internal?token=top-secret';
const publicCode = safePublicErrorCode(new Error(sensitive), 'operation_failed');
assert.equal(publicCode, 'operation_failed');
assert.equal(publicCode.includes('super-secret'), false);
assert.equal(publicCode.includes('top-secret'), false);
assert.equal(publicCode.includes('db.example'), false);

assert.equal(safeHealthDetail(true), 'ready');
assert.equal(safeHealthDetail(false), 'unavailable');

console.error('[ultron-mcp] safe error boundary self-test PASS');
