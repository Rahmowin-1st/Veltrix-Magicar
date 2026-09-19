import { GeminiLiveTokenIssuer } from './gemini-live-token.js';

async function main(): Promise<void> {
  let requestUrl = '';
  let requestInit: RequestInit | undefined;
  const fakeFetch: typeof fetch = async (input, init) => {
    requestUrl = String(input);
    requestInit = init;
    return new Response(JSON.stringify({ name: 'ephemeral-test-token' }), {
      status: 200,
      headers: { 'content-type': 'application/json' }
    });
  };

  const issuer = new GeminiLiveTokenIssuer('server-secret', fakeFetch);
  const now = new Date('2026-09-19T06:00:00.000Z');
  const token = await issuer.issue(now);
  assert(token.token === 'ephemeral-test-token', 'token must come from provisioning response');
  assert(token.model === 'gemini-3.8-live', 'car live model must be locked');
  assert(token.newSessionExpireTime === '2026-09-19T06:01:00.000Z', 'new session TTL must be one minute');
  assert(token.expireTime === '2026-09-19T06:30:00.000Z', 'connection token TTL must be thirty minutes');
  assert(requestUrl === 'https://generativelanguage.googleapis.com/v1beta/auth_tokens', 'official provisioning endpoint expected');

  const headers = new Headers(requestInit?.headers);
  assert(headers.get('x-goog-api-key') === 'server-secret', 'server key must be sent only to Google');
  const body = JSON.parse(String(requestInit?.body)) as {
    uses: number;
    expireTime: string;
    newSessionExpireTime: string;
    liveConnectConstraints?: unknown;
  };
  assert(body.uses === 1, 'token must be single use');
  assert(body.expireTime === '2026-09-19T06:30:00.000Z', 'token must have bounded message lifetime');
  assert(body.newSessionExpireTime === '2026-09-19T06:01:00.000Z', 'token must have short new-session lifetime');
  assert(body.liveConnectConstraints === undefined, 'client setup must not be overwritten by empty-mask constraints');

  const absent = GeminiLiveTokenIssuer.fromEnv({});
  assert(absent === undefined, 'issuer must stay disabled without the dedicated live key');
  console.log('gemini-live-token selftest: ok');
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

void main();
