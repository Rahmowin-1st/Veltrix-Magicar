import { aiProvidersFromEnv, type BackendAiProviderConfig } from './ai-planner.js';
import { GeminiLiveTokenIssuer } from './gemini-live-token.js';

export interface AiStartupSmokeResult {
  ok: boolean;
  checks: Array<{ name: string; ok: boolean; detail: string }>;
}

async function testChatProvider(
  provider: BackendAiProviderConfig,
  modelId = provider.modelId
): Promise<{ ok: boolean; detail: string }> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20_000);
  try {
    const response = await fetch(`${provider.baseUrl.replace(/\/+$/, '')}/chat/completions`, {
      method: 'POST',
      redirect: 'error',
      signal: controller.signal,
      headers: {
        Authorization: `Bearer ${provider.apiKey}`,
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        model: modelId,
        temperature: 0,
        max_tokens: 8,
        messages: [{ role: 'user', content: 'Reply exactly OK.' }]
      })
    });
    if (!response.ok) return { ok: false, detail: `HTTP_${response.status}` };
    return { ok: true, detail: modelId };
  } catch (error) {
    const code = error instanceof Error && error.name === 'AbortError'
      ? 'TIMEOUT'
      : 'IO_ERROR';
    return { ok: false, detail: code };
  } finally {
    clearTimeout(timeout);
  }
}

export async function runAiStartupSmoke(
  env: NodeJS.ProcessEnv = process.env
): Promise<AiStartupSmokeResult> {
  const checks: AiStartupSmokeResult['checks'] = [];
  const providers = aiProvidersFromEnv(env);

  for (const provider of providers) {
    const result = await testChatProvider(provider);
    checks.push({ name: provider.id, ...result });

    if (provider.id.startsWith('groq.')) {
      const deepModel = env.ULTRON_AI_GROQ_DEEP_MODEL?.trim();
      if (deepModel && deepModel !== provider.modelId) {
        const deep = await testChatProvider(provider, deepModel);
        checks.push({ name: 'groq.deep', ...deep });
      }
    }
  }

  const live = GeminiLiveTokenIssuer.fromEnv(env);
  if (!live) {
    checks.push({ name: 'gemini.live', ok: false, detail: 'NOT_CONFIGURED' });
  } else {
    try {
      const token = await live.issue();
      checks.push({
        name: 'gemini.live',
        ok: Boolean(token.token && token.model),
        detail: token.model || 'INVALID_TOKEN'
      });
    } catch (error) {
      const detail = error instanceof Error
        ? error.message.replace(/[^A-Za-z0-9_.:-]/g, '_').slice(0, 80)
        : 'UNKNOWN';
      checks.push({ name: 'gemini.live', ok: false, detail });
    }
  }

  return {
    ok: checks.length >= 3 && checks.every(check => check.ok),
    checks
  };
}
