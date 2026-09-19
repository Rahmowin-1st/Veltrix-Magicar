export class SubmissionRateLimitError extends Error {
  constructor(readonly retryAfterSeconds: number) {
    super(`rate_limited: retry after ${retryAfterSeconds}s`);
    this.name = 'SubmissionRateLimitError';
  }
}

interface Bucket {
  windowStartMs: number;
  count: number;
}

/**
 * Small per-process abuse bound for authenticated operations.
 * Production gateways may add a distributed edge limit, but every server
 * instance remains bounded independently and never relies on proxy policy.
 */
export class SubmissionRateLimiter {
  private readonly buckets = new Map<string, Bucket>();

  constructor(
    readonly limitPerWindow: number,
    readonly windowMs: number = 60_000,
    readonly maxBuckets: number = 4_096,
    private readonly now: () => number = Date.now
  ) {
    if (!Number.isInteger(limitPerWindow) || limitPerWindow < 1) throw new Error('submission rate limit must be a positive integer');
    if (!Number.isInteger(windowMs) || windowMs < 1_000) throw new Error('submission rate window must be at least 1000ms');
    if (!Number.isInteger(maxBuckets) || maxBuckets < 1) throw new Error('submission rate bucket bound must be positive');
  }

  assertAllowed(key: string): void {
    const normalizedKey = key.trim();
    if (!normalizedKey) throw new Error('submission rate key is required');
    const now = this.now();
    this.pruneExpired(now);

    const existing = this.buckets.get(normalizedKey);
    if (!existing || now - existing.windowStartMs >= this.windowMs) {
      this.ensureCapacity();
      this.buckets.set(normalizedKey, { windowStartMs: now, count: 1 });
      return;
    }

    if (existing.count >= this.limitPerWindow) {
      const remainingMs = Math.max(1, this.windowMs - (now - existing.windowStartMs));
      throw new SubmissionRateLimitError(Math.max(1, Math.ceil(remainingMs / 1_000)));
    }
    existing.count += 1;
  }

  private pruneExpired(now: number): void {
    for (const [key, bucket] of this.buckets) {
      if (now - bucket.windowStartMs >= this.windowMs) this.buckets.delete(key);
    }
  }

  private ensureCapacity(): void {
    while (this.buckets.size >= this.maxBuckets) {
      const oldest = this.buckets.keys().next().value as string | undefined;
      if (!oldest) return;
      this.buckets.delete(oldest);
    }
  }
}

export function parseSubmissionRateLimit(value: string | undefined, fallback: number = 60): number {
  const normalized = value?.trim();
  if (!normalized) return fallback;
  const parsed = Number(normalized);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 10_000) {
    throw new Error('ULTRON_TASK_SUBMIT_RATE_PER_MINUTE must be an integer from 1 to 10000');
  }
  return parsed;
}

export function parseBoundedRateLimit(
  value: string | undefined,
  envName: string,
  fallback: number
): number {
  const normalized = value?.trim();
  if (!normalized) return fallback;
  const parsed = Number(normalized);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 10_000) {
    throw new Error(`${envName} must be an integer from 1 to 10000`);
  }
  return parsed;
}
