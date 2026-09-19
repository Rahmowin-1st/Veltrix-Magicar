import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const entry = fileURLToPath(new URL('./production-entry.js', import.meta.url));
const now = Math.floor(Date.now() / 1000);

function cleanEnvironment(): NodeJS.ProcessEnv {
  const env = { ...process.env };
  for (const key of [
    'ULTRON_DEVICE_TOKEN_SIGNING_SECRET',
    'ULTRON_OWNER_PRINCIPAL_ID',
    'ULTRON_PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER',
    'ULTRON_PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON',
    'ULTRON_ANDROID_ALLOWED_CERT_SHA256',
    'ULTRON_ANDROID_PACKAGE_NAME',
    'ULTRON_DEVICE_TOKEN_TTL_SECONDS',
    'ULTRON_ANDROID_ALLOWED_BUILD_SHAS',
    'ULTRON_RELEASE_SHA',
    'RENDER_GIT_COMMIT'
  ]) delete env[key];
  return env;
}

const noPrincipal = spawnSync(process.execPath, [entry], {
  encoding: 'utf8',
  env: {
    ...cleanEnvironment(),
    NODE_ENV: 'production',
    ULTRON_BACKEND: 'bridge',
    ULTRON_MCP_TOKENS_JSON: '{}',
    ULTRON_DEVICE_TOKENS_JSON: '{}'
  }
});
assert.notEqual(noPrincipal.status, 0, 'production entry must reject an empty principal registry');
assert.match(noPrincipal.stderr, /at least one active principal bearer token/);

const principalOnly = spawnSync(process.execPath, [entry], {
  encoding: 'utf8',
  env: {
    ...cleanEnvironment(),
    NODE_ENV: 'production',
    ULTRON_BACKEND: 'bridge',
    ULTRON_MCP_TOKENS_JSON: JSON.stringify({
      'self-test-principal-token': {
        clientId: 'self-test-client',
        principalId: 'self-test-owner',
        principalKind: 'owner',
        deviceOwnerPrincipalId: 'self-test-owner',
        scopes: ['mcp'],
        expiresAt: now + 600
      }
    }),
    ULTRON_DEVICE_TOKENS_JSON: '{}'
  }
});
assert.notEqual(principalOnly.status, 0, 'production bridge entry must reject missing device auth');
assert.match(principalOnly.stderr, /active device bearer token or secure device enrollment/);

console.error('[ultron-mcp] production entry self-test PASS');
