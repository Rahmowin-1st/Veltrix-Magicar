import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

import type { ControlProfile, DeviceKind } from './types.js';

export interface DeviceTokenConfig {
  deviceId: string;
  ownerPrincipalId: string;
  kind: DeviceKind;
  displayName: string;
  allowedCapabilities: string[];
  controlProfile?: ControlProfile;
  expiresAt?: number;
}

export interface DeviceBinding {
  deviceId: string;
  ownerPrincipalId: string;
  kind: DeviceKind;
  displayName: string;
  allowedCapabilities: readonly string[];
  controlProfile: ControlProfile;
}

interface SignedDeviceTokenPayload extends DeviceTokenConfig {
  v: 1;
  iat: number;
  exp: number;
}

function tokenDigest(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

function signatureFor(payloadPart: string, secret: string): string {
  return createHmac('sha256', secret)
    .update(`${SIGNED_TOKEN_PREFIX}.${payloadPart}`)
    .digest('base64url');
}

export function issueSignedDeviceToken(
  config: Omit<DeviceTokenConfig, 'expiresAt'>,
  secret: string,
  ttlSeconds = 30 * 24 * 60 * 60,
  nowEpochSeconds = Math.floor(Date.now() / 1000)
): { token: string; expiresAt: number } {
  validateSigningSecret(secret);
  validateConfig(config);
  if (!Number.isInteger(ttlSeconds) || ttlSeconds < 300 || ttlSeconds > 365 * 24 * 60 * 60) {
    throw new Error('Device token TTL must be between 300 seconds and 365 days');
  }
  const expiresAt = nowEpochSeconds + ttlSeconds;
  const payload: SignedDeviceTokenPayload = {
    ...config,
    v: 1,
    iat: nowEpochSeconds,
    exp: expiresAt
  };
  const payloadPart = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const signature = signatureFor(payloadPart, secret);
  return {
    token: `${SIGNED_TOKEN_PREFIX}.${payloadPart}.${signature}`,
    expiresAt
  };
}

export class DeviceTokenRegistry {
  private readonly byDigest = new Map<string, DeviceTokenConfig>();

  constructor(
    configByToken: Readonly<Record<string, DeviceTokenConfig>>,
    private readonly signingSecret?: string
  ) {
    for (const [token, config] of Object.entries(configByToken)) {
      if (!token.trim()) throw new Error('Device token must not be blank');
      validateConfig(config);
      this.byDigest.set(tokenDigest(token), { ...config, allowedCapabilities: [...config.allowedCapabilities] });
    }
    if (signingSecret?.trim()) validateSigningSecret(signingSecret);
  }

  static fromEnv(
    raw = process.env.ULTRON_DEVICE_TOKENS_JSON,
    signingSecret = process.env.ULTRON_DEVICE_TOKEN_SIGNING_SECRET
  ): DeviceTokenRegistry {
    if (!raw?.trim()) return new DeviceTokenRegistry({}, signingSecret?.trim() || undefined);
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new Error('ULTRON_DEVICE_TOKENS_JSON must be valid JSON');
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('ULTRON_DEVICE_TOKENS_JSON must be an object keyed by bearer token');
    }
    return new DeviceTokenRegistry(
      parsed as Record<string, DeviceTokenConfig>,
      signingSecret?.trim() || undefined
    );
  }

  activeCount(nowEpochSeconds = Math.floor(Date.now() / 1000)): number {
    let count = 0;
    for (const config of this.byDigest.values()) {
      if (config.expiresAt === undefined || config.expiresAt > nowEpochSeconds) count += 1;
    }
    return count;
  }

  supportsSignedTokens(): boolean {
    return Boolean(this.signingSecret);
  }

  resolveToken(token: string): DeviceBinding | undefined {
    const staticConfig = this.byDigest.get(tokenDigest(token));
    if (staticConfig) {
      if (staticConfig.expiresAt !== undefined && staticConfig.expiresAt <= Math.floor(Date.now() / 1000)) {
        return undefined;
      }
      return toBinding(staticConfig);
    }
    return this.signingSecret ? resolveSignedToken(token, this.signingSecret) : undefined;
  }
}

function resolveSignedToken(token: string, secret: string): DeviceBinding | undefined {
  const parts = token.trim().split('.');
  if (parts.length !== 3 || parts[0] !== SIGNED_TOKEN_PREFIX) return undefined;
  const [, payloadPart, providedSignature] = parts;
  if (!payloadPart || !providedSignature) return undefined;

  const expectedSignature = signatureFor(payloadPart, secret);
  const expected = Buffer.from(expectedSignature, 'utf8');
  const provided = Buffer.from(providedSignature, 'utf8');
  if (expected.length !== provided.length || !timingSafeEqual(expected, provided)) return undefined;

  let payload: SignedDeviceTokenPayload;
  try {
    payload = JSON.parse(Buffer.from(payloadPart, 'base64url').toString('utf8')) as SignedDeviceTokenPayload;
  } catch {
    return undefined;
  }
  if (payload.v !== 1 || !Number.isInteger(payload.iat) || !Number.isInteger(payload.exp)) return undefined;
  const now = Math.floor(Date.now() / 1000);
  if (payload.exp <= now || payload.iat > now + 300 || payload.exp <= payload.iat) return undefined;
  try {
    validateConfig({ ...payload, expiresAt: payload.exp });
  } catch {
    return undefined;
  }
  return toBinding(payload);
}

function toBinding(config: DeviceTokenConfig): DeviceBinding {
  return {
    deviceId: config.deviceId,
    ownerPrincipalId: config.ownerPrincipalId,
    kind: config.kind,
    displayName: config.displayName,
    allowedCapabilities: [...config.allowedCapabilities],
    controlProfile: config.controlProfile ?? 'ASK_EACH_ACTION'
  };
}

function validateSigningSecret(secret: string): void {
  if (Buffer.byteLength(secret, 'utf8') < 32) {
    throw new Error('ULTRON_DEVICE_TOKEN_SIGNING_SECRET must contain at least 32 bytes');
  }
}

function validateConfig(config: DeviceTokenConfig): void {
  if (!config || typeof config !== 'object') throw new Error('Invalid device token binding');
  if (!config.deviceId?.trim()) throw new Error('Device token binding requires deviceId');
  if (!config.ownerPrincipalId?.trim()) throw new Error('Device token binding requires ownerPrincipalId');
  if (config.kind !== 'PHONE' && config.kind !== 'DESKTOP' && config.kind !== 'CLOUD') {
    throw new Error(`Invalid device kind: ${String(config.kind)}`);
  }
  if (!config.displayName?.trim()) throw new Error('Device token binding requires displayName');
  if (!Array.isArray(config.allowedCapabilities) || config.allowedCapabilities.length > 256) {
    throw new Error('Device token binding requires bounded allowedCapabilities');
  }
  if (config.allowedCapabilities.some(value => typeof value !== 'string' || !value.trim() || value.length > 200)) {
    throw new Error('Invalid device capability scope');
  }
  if (config.controlProfile !== undefined && !['READ_ONLY', 'ASK_EACH_ACTION', 'MAX_APPROVED'].includes(config.controlProfile)) {
    throw new Error(`Invalid device controlProfile: ${String(config.controlProfile)}`);
  }
  if (config.expiresAt !== undefined && (!Number.isFinite(config.expiresAt) || config.expiresAt <= 0)) {
    throw new Error('Device token binding expiresAt must be a positive Unix timestamp');
  }
}

const SIGNED_TOKEN_PREFIX = 'v1';
