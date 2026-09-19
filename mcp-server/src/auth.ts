import { createHash } from 'node:crypto';

import type { OAuthTokenVerifier } from '@modelcontextprotocol/express';
import type { AuthInfo } from '@modelcontextprotocol/server';
import { OAuthError, OAuthErrorCode } from '@modelcontextprotocol/server';

import type { PrincipalBinding, PrincipalKind } from './types.js';

interface TokenConfig {
  clientId: string;
  principalId: string;
  principalKind?: PrincipalKind;
  displayName?: string;
  deviceOwnerPrincipalId: string;
  allowedDeviceIds?: string[];
  scopes: string[];
  expiresAt: number;
}

function tokenDigest(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

export class TokenBindingRegistry {
  private readonly byDigest = new Map<string, TokenConfig>();

  constructor(configByToken: Readonly<Record<string, TokenConfig>>) {
    for (const [token, config] of Object.entries(configByToken)) {
      if (!token.trim()) throw new Error('MCP token must not be blank');
      validateTokenConfig(config);
      this.byDigest.set(tokenDigest(token), { ...config });
    }
  }

  static fromEnv(
    raw = process.env.ULTRON_MCP_TOKENS_JSON,
    env: NodeJS.ProcessEnv = process.env,
    nowEpochSeconds = Math.floor(Date.now() / 1000)
  ): TokenBindingRegistry {
    const configByToken = parseTokenConfigMap(raw);
    const acceptanceToken = env.VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN?.trim();
    const acceptanceExpiryRaw = env.VELTRIX_ACCEPTANCE_PRINCIPAL_TOKEN_EXPIRES_AT?.trim();

    if (acceptanceToken || acceptanceExpiryRaw) {
      if (!acceptanceToken || !acceptanceExpiryRaw) {
        throw new Error('Acceptance principal token and expiry must be configured together');
      }
      const ownerPrincipalId = env.ULTRON_OWNER_PRINCIPAL_ID?.trim();
      if (!ownerPrincipalId) throw new Error('ULTRON_OWNER_PRINCIPAL_ID is required for acceptance principal');
      const expiresAt = Number(acceptanceExpiryRaw);
      if (!Number.isInteger(expiresAt) || expiresAt <= 0) {
        throw new Error('Acceptance principal token expiry must be a Unix timestamp');
      }
      if (expiresAt > nowEpochSeconds + MAX_ACCEPTANCE_TOKEN_TTL_SECONDS) {
        throw new Error('Acceptance principal token expiry exceeds the 2-hour maximum');
      }
      if (expiresAt > nowEpochSeconds) {
        configByToken[acceptanceToken] = {
          clientId: 'veltrix-physical-acceptance',
          principalId: ownerPrincipalId,
          principalKind: 'owner',
          displayName: 'Veltrix Physical Acceptance',
          deviceOwnerPrincipalId: ownerPrincipalId,
          allowedDeviceIds: [],
          scopes: ['mcp', 'ultron:tasks', 'ultron:devices', 'ultron:control-request'],
          expiresAt
        };
      }
    }

    return new TokenBindingRegistry(configByToken);
  }

  activeCount(nowEpochSeconds = Math.floor(Date.now() / 1000)): number {
    let count = 0;
    for (const config of this.byDigest.values()) {
      if (config.expiresAt > nowEpochSeconds) count += 1;
    }
    return count;
  }

  resolveToken(token: string): PrincipalBinding | undefined {
    const config = this.byDigest.get(tokenDigest(token));
    if (!config) return undefined;
    if (config.expiresAt <= Math.floor(Date.now() / 1000)) return undefined;
    return bindingFromConfig(config);
  }

  verifier(): OAuthTokenVerifier {
    return {
      verifyAccessToken: async (token: string): Promise<AuthInfo> => {
        const binding = this.resolveToken(token);
        if (!binding) {
          throw new OAuthError(OAuthErrorCode.InvalidToken, 'unknown or expired token');
        }
        const config = this.byDigest.get(tokenDigest(token));
        if (!config) throw new OAuthError(OAuthErrorCode.InvalidToken, 'unknown token');
        return {
          token,
          clientId: binding.clientId,
          scopes: [...binding.scopes],
          expiresAt: config.expiresAt
        };
      }
    };
  }
}

export function bindingFromAuthInfo(
  registry: TokenBindingRegistry,
  authInfo: AuthInfo | undefined
): PrincipalBinding | undefined {
  if (!authInfo?.token) return undefined;
  return registry.resolveToken(authInfo.token);
}

export function stdioBindingFromEnv(): PrincipalBinding {
  const principalId = requiredEnv('ULTRON_STDIO_PRINCIPAL_ID');
  const deviceOwnerPrincipalId = process.env.ULTRON_STDIO_DEVICE_OWNER_ID?.trim() || principalId;
  const principalKind = parsePrincipalKind(process.env.ULTRON_STDIO_PRINCIPAL_KIND ?? 'agent');
  const scopes = splitCsv(process.env.ULTRON_STDIO_SCOPES ?? 'mcp,ultron:tasks,ultron:devices');
  const allowedDeviceIds = splitCsv(process.env.ULTRON_STDIO_ALLOWED_DEVICE_IDS ?? '');
  return {
    clientId: process.env.ULTRON_STDIO_CLIENT_ID?.trim() || `stdio:${principalId}`,
    principalId,
    principalKind,
    displayName: process.env.ULTRON_STDIO_DISPLAY_NAME?.trim() || principalId,
    deviceOwnerPrincipalId,
    allowedDeviceIds,
    scopes
  };
}

function parseTokenConfigMap(raw: string | undefined): Record<string, TokenConfig> {
  if (!raw?.trim()) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error('ULTRON_MCP_TOKENS_JSON must be valid JSON');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('ULTRON_MCP_TOKENS_JSON must be an object keyed by bearer token');
  }
  return { ...(parsed as Record<string, TokenConfig>) };
}

function bindingFromConfig(config: TokenConfig): PrincipalBinding {
  return {
    clientId: config.clientId,
    principalId: config.principalId,
    principalKind: config.principalKind ?? 'agent',
    displayName: config.displayName ?? config.principalId,
    deviceOwnerPrincipalId: config.deviceOwnerPrincipalId,
    allowedDeviceIds: config.allowedDeviceIds ?? [],
    scopes: config.scopes
  };
}

function validateTokenConfig(config: TokenConfig): void {
  if (!config || typeof config !== 'object') throw new Error('Invalid MCP token binding');
  if (!config.clientId?.trim()) throw new Error('MCP token binding requires clientId');
  if (!config.principalId?.trim()) throw new Error('MCP token binding requires principalId');
  if (!config.deviceOwnerPrincipalId?.trim()) throw new Error('MCP token binding requires deviceOwnerPrincipalId');
  if (!Array.isArray(config.scopes) || !config.scopes.includes('mcp')) {
    throw new Error('MCP token binding scopes must include mcp');
  }
  if (!Number.isFinite(config.expiresAt) || config.expiresAt <= 0) {
    throw new Error('MCP token binding requires Unix expiresAt');
  }
  if (config.principalKind !== undefined) parsePrincipalKind(config.principalKind);
}

function parsePrincipalKind(value: string): PrincipalKind {
  const normalized = value.trim().toLowerCase();
  if (normalized === 'owner' || normalized === 'user' || normalized === 'agent' || normalized === 'system') {
    return normalized;
  }
  throw new Error(`Invalid principal kind: ${value}`);
}

function splitCsv(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required for stdio mode`);
  return value;
}

const MAX_ACCEPTANCE_TOKEN_TTL_SECONDS = 2 * 60 * 60;
