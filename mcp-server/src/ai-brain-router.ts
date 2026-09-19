import { providerFamilyFromId, type AiProviderFamily } from './ai-provider-pool.js';
import type { BackendAiProviderConfig } from './ai-planner.js';

export const AI_BRAIN_ROLES = [
  'MAIN_ASSISTANT',
  'PLANNER',
  'VISION',
  'FAST_WORKER',
  'MEMORY_SEARCHER',
  'DEEP_VERIFIER'
] as const;

export type AiBrainRole = typeof AI_BRAIN_ROLES[number];

export interface AiBrainRoleStatus {
  ready: boolean;
  primaryFamily: AiProviderFamily | null;
  candidateCount: number;
  families: AiProviderFamily[];
  models: string[];
}

export interface AiBrainRouterStatus {
  enabled: boolean;
  providerCount: number;
  familyCount: number;
  roles: Record<AiBrainRole, AiBrainRoleStatus>;
}

interface RolePolicy {
  families: readonly AiProviderFamily[];
  requireVision: boolean;
}

const ROLE_POLICIES: Record<AiBrainRole, RolePolicy> = {
  MAIN_ASSISTANT: {
    families: ['gemini', 'groq'],
    requireVision: false
  },
  PLANNER: {
    families: ['gemini', 'groq'],
    requireVision: false
  },
  VISION: {
    families: ['gemini'],
    requireVision: true
  },
  FAST_WORKER: {
    families: ['groq', 'gemini'],
    requireVision: false
  },
  MEMORY_SEARCHER: {
    families: ['groq', 'gemini'],
    requireVision: false
  },
  DEEP_VERIFIER: {
    families: ['groq', 'gemini'],
    requireVision: false
  }
};

/**
 * Model-independent role router for the backend-only AI pool.
 *
 * The router never receives executor authority and never exposes credentials in its
 * status surface. It only chooses an ordered set of already-validated provider
 * configurations for an internal cognitive role. Device policy, owner HARD DENY,
 * planner validation and execution permission remain downstream mandatory gates.
 *
 * Multiple credentials inside one provider family remain reliability slots only.
 * Provider-family quota behavior is enforced by the caller's existing fallback loop.
 */
export class AiBrainRouter {
  private readonly providers: readonly BackendAiProviderConfig[];

  constructor(
    providers: readonly BackendAiProviderConfig[],
    private readonly env: NodeJS.ProcessEnv = process.env
  ) {
    this.providers = [...providers].sort((left, right) => left.priority - right.priority);
  }

  route(role: AiBrainRole): BackendAiProviderConfig[] {
    const policy = ROLE_POLICIES[role];
    const familyRank = new Map(policy.families.map((family, index) => [family, index] as const));

    return this.providers
      .filter(provider => {
        const family = providerFamilyFromId(provider.id);
        return family !== undefined && familyRank.has(family);
      })
      .filter(provider => !policy.requireVision || provider.supportsVision)
      .map(provider => this.forRole(role, provider))
      .sort((left, right) => {
        const leftFamily = providerFamilyFromId(left.id);
        const rightFamily = providerFamilyFromId(right.id);
        const leftRank = leftFamily === undefined ? Number.MAX_SAFE_INTEGER : (familyRank.get(leftFamily) ?? Number.MAX_SAFE_INTEGER);
        const rightRank = rightFamily === undefined ? Number.MAX_SAFE_INTEGER : (familyRank.get(rightFamily) ?? Number.MAX_SAFE_INTEGER);
        return leftRank - rightRank || left.priority - right.priority;
      });
  }

  status(): AiBrainRouterStatus {
    const families = new Set(
      this.providers
        .map(provider => providerFamilyFromId(provider.id))
        .filter((family): family is AiProviderFamily => family !== undefined)
    );

    const roles = Object.fromEntries(
      AI_BRAIN_ROLES.map(role => {
        const candidates = this.route(role);
        const candidateFamilies = candidates
          .map(candidate => providerFamilyFromId(candidate.id))
          .filter((family): family is AiProviderFamily => family !== undefined);
        return [
          role,
          {
            ready: candidates.length > 0,
            primaryFamily: candidateFamilies[0] ?? null,
            candidateCount: candidates.length,
            families: [...new Set(candidateFamilies)],
            models: [...new Set(candidates.map(candidate => candidate.modelId))]
          } satisfies AiBrainRoleStatus
        ];
      })
    ) as Record<AiBrainRole, AiBrainRoleStatus>;

    return {
      enabled: this.providers.length > 0,
      providerCount: this.providers.length,
      familyCount: families.size,
      roles
    };
  }

  private forRole(role: AiBrainRole, provider: BackendAiProviderConfig): BackendAiProviderConfig {
    const family = providerFamilyFromId(provider.id);
    if (role !== 'DEEP_VERIFIER') return { ...provider };

    if (family === 'groq') {
      return {
        ...provider,
        modelId: this.env.ULTRON_AI_GROQ_DEEP_MODEL?.trim() || 'openai/gpt-oss-120b'
      };
    }
    return { ...provider };
  }
}
