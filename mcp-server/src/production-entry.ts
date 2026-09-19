import { BackendAiPlanner } from './ai-planner.js';
import { TokenBindingRegistry } from './auth.js';
import { DeviceTokenRegistry } from './device-auth.js';
import { assertProductionAuthPreflight } from './production-preflight.js';
import { secureDeviceBootstrapConfiguredFromEnv } from './secure-bootstrap.js';

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

await import('./http.js');
