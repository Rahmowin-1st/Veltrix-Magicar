export interface GeminiLiveToken {
  token: string;
  model: string;
  expireTime: string;
  newSessionExpireTime: string;
}

export type LiveTokenFetch = typeof fetch;

/**
 * Server-side issuer for short-lived Gemini Live credentials.
 *
 * The long-lived Gemini key never leaves the backend. Tokens are single-use
 * and intentionally short-lived. The Android client supplies the Live setup
 * (system instruction, tools, transcription and AUDIO modality) so those fields
 * are not silently overridden by an empty-field-mask constrained token.
 */
export class GeminiLiveTokenIssuer {
  constructor(
    private readonly apiKey: string,
    private readonly fetchImpl: LiveTokenFetch = fetch,
    private readonly endpoint = 'https://generativelanguage.googleapis.com/v1beta/auth_tokens',
    private readonly model = 'gemini-3.8-live'
  ) {
    if (!apiKey.trim()) throw new Error('Gemini Live API key is required');
    const url = new URL(endpoint);
    if (url.protocol !== 'https:' || url.username || url.password) {
      throw new Error('Gemini Live token endpoint must use clean HTTPS');
    }
  }

  static fromEnv(
    env: NodeJS.ProcessEnv = process.env,
    fetchImpl: LiveTokenFetch = fetch
  ): GeminiLiveTokenIssuer | undefined {
    const key = env.ULTRON_AI_GEMINI_LIVE_API_KEY?.trim();
    if (!key) return undefined;
    return new GeminiLiveTokenIssuer(
      key,
      fetchImpl,
      env.ULTRON_AI_GEMINI_AUTH_TOKEN_URL?.trim()
        || 'https://generativelanguage.googleapis.com/v1beta/auth_tokens',
      env.ULTRON_AI_GEMINI_LIVE_MODEL?.trim() || 'gemini-3.8-live'
    );
  }

  async issue(now = new Date()): Promise<GeminiLiveToken> {
    const expireTime = new Date(now.getTime() + 30 * 60 * 1000).toISOString();
    const newSessionExpireTime = new Date(now.getTime() + 60 * 1000).toISOString();
    const response = await this.fetchImpl(this.endpoint, {
      method: 'POST',
      redirect: 'error',
      headers: {
        'x-goog-api-key': this.apiKey,
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify({
        uses: 1,
        expireTime,
        newSessionExpireTime
      })
    });
    if (!response.ok) throw new Error(`Gemini Live token provisioning failed: HTTP ${response.status}`);

    const raw = await response.json() as unknown;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
      throw new Error('Gemini Live token response was invalid');
    }
    const name = (raw as { name?: unknown }).name;
    if (typeof name !== 'string' || !name.trim() || name.length > 4096) {
      throw new Error('Gemini Live token response did not contain a token');
    }
    return {
      token: name.trim(),
      model: this.model,
      expireTime,
      newSessionExpireTime
    };
  }
}
