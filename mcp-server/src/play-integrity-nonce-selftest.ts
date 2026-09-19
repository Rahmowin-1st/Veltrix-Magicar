import assert from 'node:assert/strict';

import { classifyPlayIntegrityNonceMismatch } from './play-integrity.js';

const expected = 'A'.repeat(43);

assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, expected),
  undefined,
  'exact nonce match must remain accepted'
);
assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, undefined),
  'integrity_nonce_missing_from_verdict',
  'missing nonce must fail closed without exposing values'
);
assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, 'not+url/safe'),
  'integrity_nonce_malformed_from_verdict',
  'malformed returned nonce must fail closed'
);
assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, `${expected}=`),
  undefined,
  'canonical padded base64url representation of the same nonce must be accepted'
);
assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, `${expected}==`),
  'integrity_nonce_malformed_from_verdict',
  'non-canonical excess padding must still fail closed'
);
assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, 'B'.repeat(43)),
  'integrity_nonce_value_mismatch_same_length',
  'same-length different nonce must fail closed'
);
assert.equal(
  classifyPlayIntegrityNonceMismatch(expected, 'B'.repeat(44)),
  'integrity_nonce_value_mismatch_length',
  'different-length nonce must fail closed'
);

console.error('[ultron-mcp] Play Integrity nonce self-test PASS');
