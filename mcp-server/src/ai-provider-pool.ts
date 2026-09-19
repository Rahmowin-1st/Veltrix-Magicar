export type AiProviderFamily = 'gemini' | 'groq';

export interface AiProviderSlotDefinition {
  family: AiProviderFamily;
  slot: 'brain' | 'primary';
  keyEnv: string;
  priority: number;
}

/**
 * Car edition cognitive provider pool.
 *
 * Exactly three long-lived provider credentials exist in production:
 *  - ULTRON_AI_GEMINI_LIVE_API_KEY: server-only key used only to mint short-lived
 *    constrained Gemini Live tokens for the head unit.
 *  - ULTRON_AI_GEMINI_BRAIN_API_KEY: multimodal planner/vision/main-assistant.
 *  - ULTRON_AI_GROQ_API_KEY: fast worker and deep verifier.
 *
 * The Live key is intentionally NOT part of this planner pool. It can never be
 * selected for normal completion/planning calls.
 */
export const AI_PROVIDER_SLOTS: readonly AiProviderSlotDefinition[] = [
  {
    family: 'gemini',
    slot: 'brain',
    keyEnv: 'ULTRON_AI_GEMINI_BRAIN_API_KEY',
    priority: 10
  },
  {
    family: 'groq',
    slot: 'primary',
    keyEnv: 'ULTRON_AI_GROQ_API_KEY',
    priority: 20
  }
] as const;

export const GEMINI_LIVE_KEY_ENV = 'ULTRON_AI_GEMINI_LIVE_API_KEY' as const;

export function providerFamilyFromId(id: string): AiProviderFamily | undefined {
  const family = id.split('.', 1)[0];
  return family === 'gemini' || family === 'groq' ? family : undefined;
}

export function shouldSkipSiblingAfterFailure(code: string): boolean {
  // There is only one credential per cognitive provider family in the car edition,
  // but keeping this semantic makes the fallback loop explicit and future-proof.
  return code === 'PROVIDER_HTTP_429';
}
