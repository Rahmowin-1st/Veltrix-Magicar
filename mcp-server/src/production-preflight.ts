export interface ProductionAuthPreflightInput {
  nodeEnv: string | undefined;
  backendMode: string | undefined;
  activePrincipalTokens: number;
  activeDeviceTokens: number;
  secureDeviceEnrollmentConfigured?: boolean;
}

/**
 * Refuse a deceptively healthy production resource server with no usable auth.
 * Local/dev/self-test modes intentionally remain permissive.
 *
 * A production bridge needs either a pre-provisioned device credential or the
 * complete attested secure-enrollment path that can mint short-lived scoped
 * device credentials. An unauthenticated bootstrap path never weakens the
 * principal registry requirement.
 */
export function assertProductionAuthPreflight(input: ProductionAuthPreflightInput): void {
  if (input.nodeEnv?.trim().toLowerCase() !== 'production') return;
  if (!Number.isInteger(input.activePrincipalTokens) || input.activePrincipalTokens < 1) {
    throw new Error('Production ULTRON MCP requires at least one active principal bearer token');
  }

  const hasStaticDeviceCredential = Number.isInteger(input.activeDeviceTokens) && input.activeDeviceTokens >= 1;
  const hasSecureEnrollment = input.secureDeviceEnrollmentConfigured === true;
  if (
    input.backendMode?.trim().toLowerCase() === 'bridge' &&
    !hasStaticDeviceCredential &&
    !hasSecureEnrollment
  ) {
    throw new Error('Production ULTRON bridge requires at least one active device bearer token or secure device enrollment');
  }
}
