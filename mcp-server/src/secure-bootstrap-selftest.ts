import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';

import { DeviceTokenRegistry, issueSignedDeviceToken } from './device-auth.js';
import { playIntegrityConfigFromEnv } from './play-integrity.js';
import { assertProductionAuthPreflight } from './production-preflight.js';
import {
  secureDeviceBootstrapConfiguredFromEnv,
  secureEnrollmentBuildShaAllowlistFromEnv
} from './secure-bootstrap.js';

const signingSecret = 'self-test-device-signing-secret-32-bytes-minimum';
const deviceConfig = {
  deviceId: 'android-self-test',
  ownerPrincipalId: 'owner-self-test',
  kind: 'PHONE' as const,
  displayName: 'Self Test Phone',
  allowedCapabilities: ['SCREEN_OBSERVE', 'OPEN_APP'],
  controlProfile: 'ASK_EACH_ACTION' as const
};

const issued = issueSignedDeviceToken(deviceConfig, signingSecret, 600);
const registry = new DeviceTokenRegistry({}, signingSecret);
const binding = registry.resolveToken(issued.token);
assert.ok(binding, 'fresh signed device credential must resolve');
assert.equal(binding.deviceId, deviceConfig.deviceId);
assert.equal(binding.ownerPrincipalId, deviceConfig.ownerPrincipalId);
assert.deepEqual(binding.allowedCapabilities, deviceConfig.allowedCapabilities);

const parts = issued.token.split('.');
assert.equal(parts.length, 3);
const originalSignature = parts[2]!;
const replacementTail = originalSignature.endsWith('A') ? 'B' : 'A';
const tampered = `${parts[0]}.${parts[1]}.${originalSignature.slice(0, -1)}${replacementTail}`;
assert.equal(registry.resolveToken(tampered), undefined, 'tampered signature must fail closed');

const expired = issueSignedDeviceToken(
  deviceConfig,
  signingSecret,
  300,
  Math.floor(Date.now() / 1000) - 601
);
assert.equal(registry.resolveToken(expired.token), undefined, 'expired signed credential must fail closed');

const staticRegistry = new DeviceTokenRegistry({
  'legacy-static-device-token': deviceConfig
});
assert.equal(
  staticRegistry.resolveToken('legacy-static-device-token')?.deviceId,
  deviceConfig.deviceId,
  'existing static device credentials must remain backward-compatible'
);

const previousAllowed = process.env.ULTRON_ANDROID_ALLOWED_BUILD_SHAS;
const previousRelease = process.env.ULTRON_RELEASE_SHA;
const previousRenderCommit = process.env.RENDER_GIT_COMMIT;
try {
  process.env.ULTRON_ANDROID_ALLOWED_BUILD_SHAS = `${'a'.repeat(40)},${'B'.repeat(40)}`;
  process.env.ULTRON_RELEASE_SHA = 'c'.repeat(40);
  process.env.RENDER_GIT_COMMIT = 'd'.repeat(40);
  assert.deepEqual(
    [...secureEnrollmentBuildShaAllowlistFromEnv()].sort(),
    ['a'.repeat(40), 'b'.repeat(40), 'c'.repeat(40), 'd'.repeat(40)]
  );
  process.env.ULTRON_ANDROID_ALLOWED_BUILD_SHAS = 'not-a-sha';
  assert.throws(
    () => secureEnrollmentBuildShaAllowlistFromEnv(),
    /invalid Git SHA/,
    'malformed provenance configuration must fail closed'
  );
} finally {
  restoreEnv('ULTRON_ANDROID_ALLOWED_BUILD_SHAS', previousAllowed);
  restoreEnv('ULTRON_RELEASE_SHA', previousRelease);
  restoreEnv('RENDER_GIT_COMMIT', previousRenderCommit);
}

const secureEnrollmentEnvKeys = [
  'ULTRON_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER',
  'ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON',
  'ULTRON_ANDROID_ALLOWED_CERT_SHA256',
  'ULTRON_ANDROID_PACKAGE_NAME',
  'ULTRON_DEVICE_TOKEN_SIGNING_SECRET',
  'ULTRON_OWNER_PRINCIPAL_ID'
] as const;
const previousSecureEnrollmentEnv = new Map(
  secureEnrollmentEnvKeys.map(key => [key, process.env[key]] as const)
);
const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const privateKeyPem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
try {
  process.env.ULTRON_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER = '123456789012';
  process.env.ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON = JSON.stringify({
    client_email: 'self-test@project.iam.gserviceaccount.com',
    private_key: privateKeyPem,
    token_uri: 'https://oauth2.googleapis.com/token'
  });
  process.env.ULTRON_ANDROID_ALLOWED_CERT_SHA256 = 'A'.repeat(43);
  process.env.ULTRON_ANDROID_PACKAGE_NAME = 'com.veltrix.ultron';

  const validIntegrity = playIntegrityConfigFromEnv();
  assert.ok(validIntegrity, 'complete valid Play Integrity config must load');
  assert.equal(validIntegrity.cloudProjectNumber, '123456789012');
  assert.equal(validIntegrity.packageName, 'com.veltrix.ultron');
  assert.deepEqual(validIntegrity.allowedCertificateDigests, ['A'.repeat(43)]);

  delete process.env.ULTRON_ANDROID_ALLOWED_CERT_SHA256;
  assert.throws(
    () => playIntegrityConfigFromEnv(),
    /configuration is incomplete/,
    'partial Play Integrity configuration must fail instead of silently disabling enrollment'
  );
  process.env.ULTRON_ANDROID_ALLOWED_CERT_SHA256 = 'A'.repeat(43);

  process.env.ULTRON_DEVICE_TOKEN_SIGNING_SECRET = signingSecret;
  delete process.env.ULTRON_OWNER_PRINCIPAL_ID;
  assert.throws(
    () => secureDeviceBootstrapConfiguredFromEnv(),
    /configuration is incomplete/,
    'partial secure enrollment core configuration must fail closed'
  );
  process.env.ULTRON_OWNER_PRINCIPAL_ID = 'owner-self-test';

  process.env.ULTRON_ANDROID_ALLOWED_CERT_SHA256 = 'not-a-sha256-digest';
  assert.throws(
    () => playIntegrityConfigFromEnv(),
    /base64url SHA-256/,
    'malformed production signer digest must fail at startup'
  );
  process.env.ULTRON_ANDROID_ALLOWED_CERT_SHA256 = 'A'.repeat(43);

  process.env.ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON = JSON.stringify({
    client_email: 'self-test@project.iam.gserviceaccount.com',
    private_key: 'not-a-private-key',
    token_uri: 'https://oauth2.googleapis.com/token'
  });
  assert.throws(
    () => playIntegrityConfigFromEnv(),
    /valid RSA private key/,
    'malformed service-account key must fail at startup'
  );

  process.env.ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON = JSON.stringify({
    client_email: 'self-test@project.iam.gserviceaccount.com',
    private_key: privateKeyPem,
    token_uri: 'https://example.com/token'
  });
  assert.throws(
    () => playIntegrityConfigFromEnv(),
    /integrity_token_uri_invalid/,
    'service-account token endpoint must remain pinned to Google HTTPS'
  );
} finally {
  for (const key of secureEnrollmentEnvKeys) {
    restoreEnv(key, previousSecureEnrollmentEnv.get(key));
  }
}

assert.doesNotThrow(() => assertProductionAuthPreflight({
  nodeEnv: 'production',
  backendMode: 'bridge',
  activePrincipalTokens: 1,
  activeDeviceTokens: 0,
  secureDeviceEnrollmentConfigured: true
}), 'fully configured secure enrollment may replace a static device credential');

assert.throws(() => assertProductionAuthPreflight({
  nodeEnv: 'production',
  backendMode: 'bridge',
  activePrincipalTokens: 1,
  activeDeviceTokens: 0,
  secureDeviceEnrollmentConfigured: false
}), /active device bearer token or secure device enrollment/);

assert.throws(() => assertProductionAuthPreflight({
  nodeEnv: 'production',
  backendMode: 'bridge',
  activePrincipalTokens: 0,
  activeDeviceTokens: 1,
  secureDeviceEnrollmentConfigured: true
}), /at least one active principal bearer token/);

console.error('[ultron-mcp] secure bootstrap self-test PASS');

function restoreEnv(key: string, value: string | undefined): void {
  if (value === undefined) delete process.env[key];
  else process.env[key] = value;
}
