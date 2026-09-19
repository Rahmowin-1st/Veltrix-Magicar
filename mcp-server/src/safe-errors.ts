import { IdempotencyConflictError } from './idempotency.js';
import { SubmissionRateLimitError } from './submission-rate-limit.js';

/**
 * Maps internal exceptions to stable, non-sensitive public error codes.
 * Never return raw provider, database, filesystem, credential, URL, or stack text.
 */
export function safePublicErrorCode(error: unknown, fallback = 'operation_failed'): string {
  if (error instanceof SubmissionRateLimitError) return 'rate_limited';
  if (error instanceof IdempotencyConflictError) return 'idempotency_conflict';
  if (!(error instanceof Error)) return fallback;

  const message = error.message;
  if (/^Task not found$/i.test(message)) return 'not_found';
  if (/another authenticated principal/i.test(message)) return 'not_found_or_denied';
  if (/outside scope or lacks required granted capabilities/i.test(message)) return 'requested_device_unavailable';
  if (/Device must heartbeat before claiming tasks/i.test(message)) return 'device_not_ready';
  if (/Task is not assigned to this device/i.test(message)) return 'task_not_assigned';
  if (/Cannot (?:pause|resume|update) (?:terminal )?task/i.test(message)) return 'invalid_task_state';
  if (/Verified task cannot be cancelled/i.test(message)) return 'invalid_task_state';
  if (/Paused task must be resumed or cancelled/i.test(message)) return 'task_paused';
  if (/VERIFIED_DONE requires evidence/i.test(message)) return 'evidence_required';
  if (/objective is required/i.test(message)) return 'invalid_request';
  return fallback;
}

export function safeHealthDetail(ok: boolean): string {
  return ok ? 'ready' : 'unavailable';
}
