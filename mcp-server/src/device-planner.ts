import { backendPlannerRequestSchema, type BackendPlannerRequest } from './ai-planner.js';
import type { DeviceBinding } from './device-auth.js';

/** Safe contract error for authenticated device planner requests. */
export class DevicePlannerBindingError extends Error {
  constructor(readonly safeCode: 'device_binding_mismatch') {
    super(safeCode);
    this.name = 'DevicePlannerBindingError';
  }
}

/**
 * Re-establish the server-side device-token boundary before any provider call.
 * Device identity/kind must match the bearer binding. The model sees only the
 * capability intersection authorized by that token; client overclaim is dropped.
 */
export function scopeDevicePlannerRequest(
  binding: DeviceBinding,
  rawRequest: unknown
): BackendPlannerRequest {
  const parsed = backendPlannerRequestSchema.parse(rawRequest) as BackendPlannerRequest;
  if (parsed.device.id !== binding.deviceId || parsed.device.kind !== binding.kind) {
    throw new DevicePlannerBindingError('device_binding_mismatch');
  }

  const allowed = new Set(binding.allowedCapabilities);
  const effectiveCapabilities = [...new Set(
    parsed.device.effectiveCapabilities.filter(capability => allowed.has(capability))
  )].sort();

  return {
    ...parsed,
    device: {
      ...parsed.device,
      id: binding.deviceId,
      kind: binding.kind,
      effectiveCapabilities
    }
  };
}

export function parseAiPlanRateLimit(value: string | undefined, fallback = 30): number {
  const normalized = value?.trim();
  if (!normalized) return fallback;
  const parsed = Number(normalized);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 600) {
    throw new Error('ULTRON_AI_PLAN_RATE_PER_MINUTE must be an integer from 1 to 600');
  }
  return parsed;
}
