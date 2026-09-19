import { BackendAiPlanner } from './ai-planner.js';
import { TokenBindingRegistry } from './auth.js';
import { DeviceTokenRegistry } from './device-auth.js';
import { assertProductionAuthPreflight } from './production-preflight.js';
import { secureDeviceBootstrapConfiguredFromEnv } from './secure-bootstrap.js';
import { runAiStartupSmoke } from './ai-startup-smoke.js';

const principals = TokenBindingRegistry.fromEnv();
const devices = DeviceTokenRegistry.fromEnv();

assertProductionAuthPreflight({
  nodeEnv: process.env.NODE_ENV,
  backendMode: process.env.ULTRON_BACKEND,
  activePrincipalTokens: principals.activeCount(),
  activeDeviceTokens: devices.activeCount(),
  secureDeviceEnrollmentConfigured: secureDeviceBootstrapConfiguredFromEnv()
});

const aiStatus = BackendAiPlanner.fromEnv().status();
console.error(
  `[ultron-mcp] ai-planner readiness enabled=${aiStatus.enabled ? 'yes' : 'no'} providers=${aiStatus.providerCount} vision=${aiStatus.visionProviderCount}`
);

if (process.env.ULTRON_AI_STARTUP_SMOKE_TEST?.trim() === '1') {
  const smoke = await runAiStartupSmoke();
  for (const check of smoke.checks) {
    console.error(`[magicar-ai-smoke] ${check.name}=${check.ok ? 'PASS' : 'FAIL'} model=${check.detail}`);
  }
  if (!smoke.ok) throw new Error('Magicar AI startup smoke test failed');
}

await import('./http.js');
