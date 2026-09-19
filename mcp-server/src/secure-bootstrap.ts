import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';

import { Pool } from 'pg';

import { issueSignedDeviceToken } from './device-auth.js';
import {
  playIntegrityConfigFromEnv,
  verifyPlayIntegrityToken,
  type PlayIntegrityConfig,
  type VerifiedPlayIntegrity
} from './play-integrity.js';

export interface DeviceEnrollmentChallengeInput {
  installationId: string;
  buildSha: string;
}

export interface DeviceEnrollmentChallenge {
  nonce: string;
  cloudProjectNumber: string;
  expiresAt: number;
}

export interface DeviceEnrollmentInput {
  nonce: string;
  integrityToken: string;
}

export interface DevicePairingInput {
  installationId: string;
  buildSha: string;
  pairingCode: string;
}

export interface DeviceEnrollmentResult {
  bearerToken: string;
  expiresAt: number;
  deviceId: string;
  integrity: {
    appRecognitionVerdict: string;
    licensingVerdict: string;
    playProtectVerdict?: string;
  };
}

interface StoredChallenge {
  installationId: string;
  buildSha: string;
}

interface ChallengeStore {
  put(nonce: string, installationId: string, buildSha: string, expiresAtMs: number): Promise<void>;
  consume(nonce: string): Promise<StoredChallenge | undefined>;
  close(): Promise<void>;
}

export class SecureDeviceBootstrap {
  constructor(
    private readonly integrity: PlayIntegrityConfig | undefined,
    private readonly signingSecret: string,
    private readonly ownerPrincipalId: string,
    private readonly store: ChallengeStore,
    private readonly tokenTtlSeconds = 30 * 24 * 60 * 60,
    private readonly allowedBuildShas?: ReadonlySet<string>,
    private readonly pairingCode?: string
  ) {
    if (!ownerPrincipalId.trim()) throw new Error('ULTRON_OWNER_PRINCIPAL_ID is required for secure enrollment');
    if (pairingCode !== undefined && pairingCode.length < 20) {
      throw new Error('Pairing code must be at least 20 characters');
    }
  }

  static fromEnv(): SecureDeviceBootstrap | undefined {
    const integrity = playIntegrityConfigFromEnv();
    const signingSecret = process.env.ULTRON_DEVICE_TOKEN_SIGNING_SECRET?.trim();
    const ownerPrincipalId = process.env.ULTRON_OWNER_PRINCIPAL_ID?.trim();
    const pairingCode = process.env.ULTRON_DEVICE_PAIRING_CODE?.trim();
    const anyEnrollmentCoreConfigured = Boolean(integrity || signingSecret || ownerPrincipalId || pairingCode);
    if (!anyEnrollmentCoreConfigured) return undefined;
    if (!signingSecret || !ownerPrincipalId) {
      throw new Error(
        'Secure device enrollment configuration is incomplete; token signing secret and owner principal are required'
      );
    }
    if (!integrity && !pairingCode) {
      throw new Error(
        'Secure device enrollment requires either complete Play Integrity configuration or a Magicar pairing code'
      );
    }
    const ttl = parseTokenTtl(process.env.ULTRON_DEVICE_TOKEN_TTL_SECONDS);
    const allowedBuildShas = secureEnrollmentBuildShaAllowlistFromEnv();
    if (process.env.NODE_ENV?.trim().toLowerCase() === 'production' && allowedBuildShas.size === 0) {
      throw new Error('Production secure enrollment requires an approved release/build SHA');
    }
    return new SecureDeviceBootstrap(
      integrity,
      signingSecret,
      ownerPrincipalId,
      challengeStoreFromEnv(),
      ttl,
      allowedBuildShas.size > 0 ? allowedBuildShas : undefined,
      pairingCode || undefined
    );
  }

  publicConfig(): { cloudProjectNumber: string; packageName: string } {
    if (!this.integrity) throw new Error('play_integrity_not_configured');
    return {
      cloudProjectNumber: this.integrity.cloudProjectNumber,
      packageName: this.integrity.packageName
    };
  }

  async createChallenge(input: DeviceEnrollmentChallengeInput): Promise<DeviceEnrollmentChallenge> {
    if (!this.integrity) throw new Error('play_integrity_not_configured');
    const installationId = input.installationId.trim();
    const buildSha = input.buildSha.trim().toLowerCase();
    if (!INSTALLATION_ID_PATTERN.test(installationId)) throw new Error('invalid_installation_id');
    if (!BUILD_SHA_PATTERN.test(buildSha)) throw new Error('invalid_build_sha');
    if (this.allowedBuildShas && !this.allowedBuildShas.has(buildSha)) {
      throw new Error('enrollment_build_sha_not_allowed');
    }

    const random = randomBytes(32).toString('base64url');
    const nonce = createHash('sha256')
      .update(`veltrix-enroll-v1|${random}|${installationId}|${buildSha}`)
      .digest('base64url');
    const expiresAtMs = Date.now() + CHALLENGE_TTL_MS;
    await this.store.put(nonce, installationId, buildSha, expiresAtMs);
    return {
      nonce,
      cloudProjectNumber: this.integrity.cloudProjectNumber,
      expiresAt: Math.floor(expiresAtMs / 1000)
    };
  }

  async enroll(input: DeviceEnrollmentInput): Promise<DeviceEnrollmentResult> {
    if (!this.integrity) throw new Error('play_integrity_not_configured');
    const nonce = input.nonce.trim();
    if (!NONCE_PATTERN.test(nonce)) throw new Error('invalid_enrollment_nonce');
    const stored = await this.store.consume(nonce);
    if (!stored) throw new Error('enrollment_challenge_expired_or_used');
    if (this.allowedBuildShas && !this.allowedBuildShas.has(stored.buildSha)) {
      throw new Error('enrollment_build_sha_not_allowed');
    }

    const verified = await verifyPlayIntegrityToken(input.integrityToken, nonce, this.integrity);
    const token = this.issueDisplayToken(
      stored.installationId,
      verified.appRecognitionVerdict,
      verified.licensingVerdict
    );
    return {
      ...token,
      integrity: safeIntegritySummary(verified)
    };
  }

  async pairWithCode(input: DevicePairingInput): Promise<DeviceEnrollmentResult> {
    if (!this.pairingCode) throw new Error('secure_pairing_not_configured');
    const installationId = input.installationId.trim();
    const buildSha = input.buildSha.trim().toLowerCase();
    const candidate = input.pairingCode.trim();
    if (!INSTALLATION_ID_PATTERN.test(installationId)) throw new Error('invalid_installation_id');
    if (!BUILD_SHA_PATTERN.test(buildSha)) throw new Error('invalid_build_sha');
    if (this.allowedBuildShas && !this.allowedBuildShas.has(buildSha)) {
      throw new Error('enrollment_build_sha_not_allowed');
    }
    if (!safePairingCodeEqual(candidate, this.pairingCode)) {
      throw new Error('invalid_pairing_code');
    }
    return this.issueDisplayToken(installationId, 'PAIRING_CODE', 'MANUAL_PAIRING');
  }

  private issueDisplayToken(
    installationId: string,
    appRecognitionVerdict: string,
    licensingVerdict: string
  ): DeviceEnrollmentResult {
    const deviceId = `android-${createHash('sha256').update(installationId).digest('hex').slice(0, 24)}`;
    const issued = issueSignedDeviceToken(
      {
        deviceId,
        ownerPrincipalId: this.ownerPrincipalId,
        kind: 'PHONE',
        displayName: 'Veltrix Magicar',
        allowedCapabilities: DEFAULT_ANDROID_CAPABILITIES,
        controlProfile: 'MAX_APPROVED'
      },
      this.signingSecret,
      this.tokenTtlSeconds
    );
    return {
      bearerToken: issued.token,
      expiresAt: issued.expiresAt,
      deviceId,
      integrity: { appRecognitionVerdict, licensingVerdict }
    };
  }

  async close(): Promise<void> {
    await this.store.close();
  }
}

export function secureDeviceBootstrapConfiguredFromEnv(): boolean {
  const integrity = playIntegrityConfigFromEnv();
  const signingSecret = process.env.ULTRON_DEVICE_TOKEN_SIGNING_SECRET?.trim();
  const ownerPrincipalId = process.env.ULTRON_OWNER_PRINCIPAL_ID?.trim();
  const pairingCode = process.env.ULTRON_DEVICE_PAIRING_CODE?.trim();
  const anyEnrollmentCoreConfigured = Boolean(integrity || signingSecret || ownerPrincipalId || pairingCode);
  if (!anyEnrollmentCoreConfigured) return false;
  if (!signingSecret || !ownerPrincipalId) {
    throw new Error(
      'Secure device enrollment configuration is incomplete; token signing secret and owner principal are required'
    );
  }
  if (!integrity && !pairingCode) {
    throw new Error('Secure device enrollment requires Play Integrity or a Magicar pairing code');
  }
  if (process.env.NODE_ENV?.trim().toLowerCase() !== 'production') return true;
  return secureEnrollmentBuildShaAllowlistFromEnv().size > 0;
}

export function secureEnrollmentBuildShaAllowlistFromEnv(): ReadonlySet<string> {
  const rawValues = [
    ...(process.env.ULTRON_ANDROID_ALLOWED_BUILD_SHAS?.split(',') ?? []),
    process.env.ULTRON_RELEASE_SHA,
    process.env.RENDER_GIT_COMMIT
  ];
  const shas = new Set<string>();
  for (const raw of rawValues) {
    const value = raw?.trim().toLowerCase();
    if (!value) continue;
    if (!BUILD_SHA_PATTERN.test(value)) {
      throw new Error('Secure enrollment build SHA allowlist contains an invalid Git SHA');
    }
    shas.add(value);
  }
  return shas;
}

function safeIntegritySummary(verified: VerifiedPlayIntegrity): DeviceEnrollmentResult['integrity'] {
  return {
    appRecognitionVerdict: verified.appRecognitionVerdict,
    licensingVerdict: verified.licensingVerdict,
    ...(verified.playProtectVerdict ? { playProtectVerdict: verified.playProtectVerdict } : {})
  };
}

function challengeStoreFromEnv(): ChallengeStore {
  const connectionString = process.env.DATABASE_URL?.trim();
  if (connectionString) return new PostgresChallengeStore(connectionString);
  if (process.env.NODE_ENV?.trim().toLowerCase() === 'production') {
    throw new Error('Secure device enrollment requires DATABASE_URL in production');
  }
  return new MemoryChallengeStore();
}

class MemoryChallengeStore implements ChallengeStore {
  private readonly entries = new Map<string, { installationId: string; buildSha: string; expiresAtMs: number }>();

  async put(nonce: string, installationId: string, buildSha: string, expiresAtMs: number): Promise<void> {
    this.entries.set(nonceHash(nonce), { installationId, buildSha, expiresAtMs });
  }

  async consume(nonce: string): Promise<StoredChallenge | undefined> {
    const key = nonceHash(nonce);
    const entry = this.entries.get(key);
    this.entries.delete(key);
    if (!entry || entry.expiresAtMs <= Date.now()) return undefined;
    return { installationId: entry.installationId, buildSha: entry.buildSha };
  }

  async close(): Promise<void> {
    this.entries.clear();
  }
}

class PostgresChallengeStore implements ChallengeStore {
  private readonly pool: Pool;
  private ready?: Promise<void>;

  constructor(connectionString: string) {
    this.pool = new Pool({ connectionString, max: 2 });
  }

  async put(nonce: string, installationId: string, buildSha: string, expiresAtMs: number): Promise<void> {
    await this.ensureSchema();
    await this.pool.query(
      `INSERT INTO ultron_device_enrollment_challenges
        (nonce_hash, installation_id, build_sha, expires_at)
       VALUES ($1, $2, $3, to_timestamp($4 / 1000.0))
       ON CONFLICT (nonce_hash) DO NOTHING`,
      [nonceHash(nonce), installationId, buildSha, expiresAtMs]
    );
    void this.pool.query(
      `DELETE FROM ultron_device_enrollment_challenges
       WHERE expires_at < now() - interval '1 hour'`
    ).catch(() => undefined);
  }

  async consume(nonce: string): Promise<StoredChallenge | undefined> {
    await this.ensureSchema();
    const result = await this.pool.query<{ installation_id: string; build_sha: string }>(
      `UPDATE ultron_device_enrollment_challenges
       SET consumed_at = now()
       WHERE nonce_hash = $1
         AND consumed_at IS NULL
         AND expires_at > now()
       RETURNING installation_id, build_sha`,
      [nonceHash(nonce)]
    );
    const row = result.rows[0];
    return row ? { installationId: row.installation_id, buildSha: row.build_sha } : undefined;
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  private async ensureSchema(): Promise<void> {
    if (!this.ready) {
      this.ready = this.pool.query(
        `CREATE TABLE IF NOT EXISTS ultron_device_enrollment_challenges (
          nonce_hash text PRIMARY KEY,
          installation_id text NOT NULL,
          build_sha text NOT NULL,
          expires_at timestamptz NOT NULL,
          consumed_at timestamptz NULL,
          created_at timestamptz NOT NULL DEFAULT now()
        )`
      ).then(() => undefined);
    }
    await this.ready;
  }
}

function safePairingCodeEqual(candidate: string, expected: string): boolean {
  if (candidate.length < 20 || candidate.length > 128) return false;
  const left = createHash('sha256').update(candidate).digest();
  const right = createHash('sha256').update(expected).digest();
  return timingSafeEqual(left, right);
}

function nonceHash(nonce: string): string {
  return createHash('sha256').update(nonce).digest('hex');
}

function parseTokenTtl(raw: string | undefined): number {
  if (!raw?.trim()) return 30 * 24 * 60 * 60;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 300 || parsed > 365 * 24 * 60 * 60) {
    throw new Error('ULTRON_DEVICE_TOKEN_TTL_SECONDS must be between 300 and 31536000');
  }
  return parsed;
}

const CHALLENGE_TTL_MS = 2 * 60_000;
const INSTALLATION_ID_PATTERN = /^[a-zA-Z0-9._-]{16,128}$/;
const BUILD_SHA_PATTERN = /^[0-9a-f]{40}$/;
const NONCE_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;
const DEFAULT_ANDROID_CAPABILITIES = [
  'SCREEN_OBSERVE',
  'SCREEN_CAPTURE',
  'OPEN_APP',
  'APP_SWITCH',
  'UI_CLICK',
  'UI_TYPE',
  'UI_SCROLL',
  'UI_GESTURE',
  'BROWSER_NAVIGATE',
  'MICROPHONE_CAPTURE',
  'NOTIFICATION_READ',
  'FILE_READ',
  'FILE_WRITE',
  'FILE_UPLOAD',
  'FILE_DOWNLOAD',
  'CLIPBOARD_READ',
  'CLIPBOARD_WRITE',
  'PHONE_AUTOMATION',
  'BACKGROUND_TASKS'
];
