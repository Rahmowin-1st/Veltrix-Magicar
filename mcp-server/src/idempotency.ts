import { createHash } from 'node:crypto';

import type { PrincipalBinding, SubmitTaskInput } from './types.js';

export class IdempotencyConflictError extends Error {
  constructor() {
    super('Idempotency key was already used for a different task request');
    this.name = 'IdempotencyConflictError';
  }
}

/**
 * Binds a client idempotency key to the authenticated principal scope and the
 * normalized semantic task request. No timestamps or transient runtime state
 * are included, so legitimate retries remain stable across process restarts.
 */
export function taskIdempotencyFingerprint(binding: PrincipalBinding, input: SubmitTaskInput): string {
  const canonical = {
    principalId: binding.principalId,
    principalKind: binding.principalKind,
    deviceOwnerPrincipalId: binding.deviceOwnerPrincipalId,
    allowedDeviceIds: canonicalStrings(binding.allowedDeviceIds),
    scopes: canonicalStrings(binding.scopes),
    target: input.target,
    deviceId: normalizeOptional(input.deviceId),
    objective: normalizeText(input.objective),
    requiredCapabilities: canonicalStrings(input.requiredCapabilities),
    constraints: canonicalStrings(input.constraints)
  };
  return createHash('sha256').update(JSON.stringify(canonical), 'utf8').digest('hex');
}

export function assertIdempotencyFingerprint(existing: string | null | undefined, expected: string): void {
  if (!existing || existing !== expected) throw new IdempotencyConflictError();
}

function canonicalStrings(values: readonly string[]): string[] {
  return [...new Set(values.map(normalizeText).filter(Boolean))].sort();
}

function normalizeText(value: string): string {
  return value.trim().replace(/\s+/g, ' ');
}

function normalizeOptional(value: string | undefined): string | null {
  const normalized = value ? normalizeText(value) : '';
  return normalized || null;
}
