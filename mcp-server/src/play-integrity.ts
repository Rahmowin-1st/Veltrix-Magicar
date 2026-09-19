import { createPrivateKey, createSign } from 'node:crypto';

export interface PlayIntegrityConfig {
  cloudProjectNumber: string;
  packageName: string;
  allowedCertificateDigests: readonly string[];
  serviceAccount: ServiceAccountCredentials;
}

interface ServiceAccountCredentials {
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export interface VerifiedPlayIntegrity {
  appRecognitionVerdict: 'PLAY_RECOGNIZED' | 'UNRECOGNIZED_VERSION';
  licensingVerdict: string;
  deviceIntegrity: readonly string[];
  playProtectVerdict?: string;
  versionCode?: string;
}

interface TokenPayloadExternal {
  requestDetails?: {
    requestPackageName?: string;
    nonce?: string;
    timestampMillis?: string;
  };
  appIntegrity?: {
    appRecognitionVerdict?: string;
    packageName?: string;
    certificateSha256Digest?: string[];
    versionCode?: string;
  };
  accountDetails?: {
    appLicensingVerdict?: string;
  };
  deviceIntegrity?: {
    deviceRecognitionVerdict?: string[];
  };
  environmentDetails?: {
    playProtectVerdict?: string;
  };
}

let cachedAccessToken: {
  token: string;
  expiresAtMs: number;
  account: string;
  tokenUri: string;
} | undefined;

export function classifyPlayIntegrityNonceMismatch(
  expectedNonce: string,
  returnedNonce: string | undefined
): string | undefined {
  if (returnedNonce === expectedNonce) return undefined;
  if (!returnedNonce) return 'integrity_nonce_missing_from_verdict';
  if (!PLAY_INTEGRITY_NONCE_PATTERN.test(returnedNonce)) {
    return 'integrity_nonce_malformed_from_verdict';
  }
  const expectedUnpadded = expectedNonce.replace(/=+$/g, '');
  const returnedUnpadded = returnedNonce.replace(/=+$/g, '');
  if (expectedUnpadded === returnedUnpadded) {
    const requiredPadding = (4 - (expectedUnpadded.length % 4)) % 4;
    const returnedPadding = returnedNonce.length - returnedUnpadded.length;
    if (returnedPadding === 0 || returnedPadding === requiredPadding) return undefined;
    return 'integrity_nonce_malformed_from_verdict';
  }
  if (returnedNonce.length === expectedNonce.length) {
    return 'integrity_nonce_value_mismatch_same_length';
  }
  return 'integrity_nonce_value_mismatch_length';
}

export function playIntegrityConfigFromEnv(): PlayIntegrityConfig | undefined {
  const cloudProjectNumber = process.env.ULTRON_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER?.trim();
  const rawServiceAccount = process.env.ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON?.trim();
  const rawDigests = process.env.ULTRON_ANDROID_ALLOWED_CERT_SHA256?.trim();
  const configuredParts = [cloudProjectNumber, rawServiceAccount, rawDigests].filter(Boolean).length;
  if (configuredParts === 0) return undefined;
  if (configuredParts !== 3) {
    throw new Error(
      'Play Integrity configuration is incomplete; cloud project number, service account JSON, and certificate digest are required together'
    );
  }
  if (!/^\d{6,20}$/.test(cloudProjectNumber!)) {
    throw new Error('ULTRON_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER must be a numeric project number');
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(rawServiceAccount!);
  } catch {
    throw new Error('ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON must be valid JSON');
  }
  const serviceAccount = parsed as Partial<ServiceAccountCredentials>;
  if (!serviceAccount.client_email?.trim() || !serviceAccount.private_key?.trim()) {
    throw new Error('Play Integrity service account requires client_email and private_key');
  }
  if (!SERVICE_ACCOUNT_EMAIL_PATTERN.test(serviceAccount.client_email.trim())) {
    throw new Error('Play Integrity service account client_email is invalid');
  }
  try {
    const key = createPrivateKey(serviceAccount.private_key);
    if (key.asymmetricKeyType !== 'rsa') {
      throw new Error('not-rsa');
    }
  } catch {
    throw new Error('Play Integrity service account private_key must be a valid RSA private key');
  }

  const packageName = process.env.ULTRON_ANDROID_PACKAGE_NAME?.trim() || 'com.veltrix.ultron';
  if (!ANDROID_PACKAGE_PATTERN.test(packageName)) {
    throw new Error('ULTRON_ANDROID_PACKAGE_NAME is invalid');
  }

  const allowedCertificateDigests = [
    ...new Set(
      rawDigests!
        .split(',')
        .map(normalizeDigest)
        .filter(Boolean)
    )
  ];
  if (allowedCertificateDigests.length === 0) {
    throw new Error('ULTRON_ANDROID_ALLOWED_CERT_SHA256 requires at least one certificate digest');
  }
  if (allowedCertificateDigests.some(value => !CERT_SHA256_BASE64URL_PATTERN.test(value))) {
    throw new Error('ULTRON_ANDROID_ALLOWED_CERT_SHA256 must contain base64url SHA-256 digests');
  }

  const tokenUri = normalizeGoogleTokenUri(serviceAccount.token_uri);
  return {
    cloudProjectNumber: cloudProjectNumber!,
    packageName,
    allowedCertificateDigests,
    serviceAccount: {
      client_email: serviceAccount.client_email.trim(),
      private_key: serviceAccount.private_key,
      token_uri: tokenUri
    }
  };
}

export async function verifyPlayIntegrityToken(
  integrityToken: string,
  expectedNonce: string,
  config: PlayIntegrityConfig,
  nowMs = Date.now()
): Promise<VerifiedPlayIntegrity> {
  if (!integrityToken.trim()) throw new Error('integrity_token_missing');
  if (!expectedNonce.trim()) throw new Error('integrity_nonce_missing');

  const accessToken = await googleAccessToken(config.serviceAccount, nowMs);
  const response = await googleFetch(
    `https://playintegrity.googleapis.com/v1/${encodeURIComponent(config.packageName)}:decodeIntegrityToken`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${accessToken}`,
        'content-type': 'application/json',
        accept: 'application/json'
      },
      body: JSON.stringify({ integrity_token: integrityToken })
    },
    'decode'
  );
  if (!response.ok) throw new Error(`integrity_decode_${response.status}`);
  const decoded = await response.json() as { tokenPayloadExternal?: TokenPayloadExternal };
  const payload = decoded.tokenPayloadExternal;
  if (!payload) throw new Error('integrity_payload_missing');

  const request = payload.requestDetails;
  if (request?.requestPackageName !== config.packageName) throw new Error('integrity_package_mismatch');
  const nonceMismatch = classifyPlayIntegrityNonceMismatch(expectedNonce, request?.nonce);
  if (nonceMismatch) throw new Error(nonceMismatch);
  const timestampMs = Number(request?.timestampMillis);
  if (!Number.isFinite(timestampMs) || Math.abs(nowMs - timestampMs) > 2 * 60_000) {
    throw new Error('integrity_stale');
  }

  const app = payload.appIntegrity;
  if (app?.packageName !== config.packageName) throw new Error('integrity_app_package_mismatch');
  if (app.appRecognitionVerdict !== 'PLAY_RECOGNIZED' && app.appRecognitionVerdict !== 'UNRECOGNIZED_VERSION') {
    throw new Error('integrity_app_unrecognized');
  }
  const returnedDigests = (app.certificateSha256Digest ?? []).map(normalizeDigest);
  if (!config.allowedCertificateDigests.some(expected => returnedDigests.includes(expected))) {
    throw new Error('integrity_signer_mismatch');
  }

  const deviceIntegrity = payload.deviceIntegrity?.deviceRecognitionVerdict ?? [];
  if (!deviceIntegrity.includes('MEETS_DEVICE_INTEGRITY')) throw new Error('integrity_device_failed');

  const playProtectVerdict = payload.environmentDetails?.playProtectVerdict;
  if (playProtectVerdict === 'HIGH_RISK') throw new Error('integrity_play_protect_high_risk');

  return {
    appRecognitionVerdict: app.appRecognitionVerdict,
    licensingVerdict: payload.accountDetails?.appLicensingVerdict ?? 'UNEVALUATED',
    deviceIntegrity,
    ...(playProtectVerdict ? { playProtectVerdict } : {}),
    ...(app.versionCode ? { versionCode: app.versionCode } : {})
  };
}

async function googleAccessToken(credentials: ServiceAccountCredentials, nowMs: number): Promise<string> {
  const tokenUri = normalizeGoogleTokenUri(credentials.token_uri);
  if (
    cachedAccessToken &&
    cachedAccessToken.account === credentials.client_email &&
    cachedAccessToken.tokenUri === tokenUri &&
    cachedAccessToken.expiresAtMs - nowMs > 60_000
  ) {
    return cachedAccessToken.token;
  }

  const issuedAt = Math.floor(nowMs / 1000);
  const header = base64UrlJson({ alg: 'RS256', typ: 'JWT' });
  const claims = base64UrlJson({
    iss: credentials.client_email,
    scope: 'https://www.googleapis.com/auth/playintegrity',
    aud: tokenUri,
    iat: issuedAt,
    exp: issuedAt + 3600
  });
  const input = `${header}.${claims}`;
  const signer = createSign('RSA-SHA256');
  signer.update(input);
  signer.end();
  const assertion = `${input}.${signer.sign(credentials.private_key).toString('base64url')}`;
  const response = await googleFetch(
    tokenUri,
    {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion
      })
    },
    'oauth'
  );
  if (!response.ok) throw new Error(`integrity_oauth_${response.status}`);
  const body = await response.json() as { access_token?: string; expires_in?: number };
  if (!body.access_token?.trim()) throw new Error('integrity_oauth_missing_token');
  const expiresIn = Number.isFinite(body.expires_in) ? Number(body.expires_in) : 3600;
  cachedAccessToken = {
    token: body.access_token,
    expiresAtMs: nowMs + Math.max(300, expiresIn) * 1000,
    account: credentials.client_email,
    tokenUri
  };
  return body.access_token;
}

async function googleFetch(
  url: string,
  init: Parameters<typeof fetch>[1],
  phase: 'oauth' | 'decode'
): Promise<Response> {
  try {
    return await fetch(url, {
      ...init,
      signal: AbortSignal.timeout(INTEGRITY_HTTP_TIMEOUT_MS)
    });
  } catch (error) {
    const name = error instanceof Error ? error.name : '';
    if (name === 'TimeoutError' || name === 'AbortError') {
      throw new Error(`integrity_${phase}_timeout`);
    }
    throw new Error(`integrity_${phase}_network`);
  }
}

function normalizeGoogleTokenUri(raw: string | undefined): string {
  const value = raw?.trim() || DEFAULT_GOOGLE_TOKEN_URI;
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error('integrity_token_uri_invalid');
  }
  if (
    parsed.protocol !== 'https:' ||
    parsed.hostname !== 'oauth2.googleapis.com' ||
    parsed.pathname !== '/token' ||
    parsed.username ||
    parsed.password ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error('integrity_token_uri_invalid');
  }
  return parsed.toString();
}

function base64UrlJson(value: unknown): string {
  return Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
}

function normalizeDigest(value: string): string {
  return value.trim().replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

const DEFAULT_GOOGLE_TOKEN_URI = 'https://oauth2.googleapis.com/token';
const INTEGRITY_HTTP_TIMEOUT_MS = 15_000;
const CERT_SHA256_BASE64URL_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const PLAY_INTEGRITY_NONCE_PATTERN = /^[A-Za-z0-9_-]{16,500}={0,2}$/;
const SERVICE_ACCOUNT_EMAIL_PATTERN = /^[^\s@]+@[^\s@]+$/;
const ANDROID_PACKAGE_PATTERN = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
