# Veltrix ULTRON MCP Server

Dedicated MCP resource server for AI agents, coding agents, tools and automation that delegate objectives to Veltrix ULTRON.

The server exposes **task-level** tools. It deliberately does not expose raw privileged `tap`, `type`, shell or device-executor primitives. Phone/desktop execution remains behind ULTRON device routing, permission policy and proof-of-done verification.

## Transports

### HTTP

```sh
npm install
npm run build
npm start
```

Default endpoint: `http://127.0.0.1:8787/mcp`.

HTTP requires a Bearer token with the `mcp` scope. Token bindings are supplied only through `ULTRON_MCP_TOKENS_JSON` or a future external identity-provider verifier; never commit tokens to the repository.

Example shape, using placeholders rather than a real secret:

```json
{
  "<BEARER_TOKEN_FROM_SECRET_STORE>": {
    "clientId": "frontend-agent",
    "principalId": "frontend-agent",
    "principalKind": "agent",
    "displayName": "Frontend Agent",
    "deviceOwnerPrincipalId": "owner",
    "allowedDeviceIds": ["phone-main", "desktop-main"],
    "scopes": ["mcp", "ultron:tasks", "ultron:devices", "ultron:control-request"],
    "expiresAt": 1893456000
  }
}
```

For a public bind such as `ULTRON_MCP_HOST=0.0.0.0`, `ULTRON_MCP_ALLOWED_HOSTS` is mandatory. The Express adapter supplies Host/Origin protections.

### stdio

Local MCP hosts can spawn the server directly:

```sh
npm run start:stdio
```

Required environment:

- `ULTRON_STDIO_PRINCIPAL_ID`
- optional `ULTRON_STDIO_PRINCIPAL_KIND` (`owner`, `user`, `agent`, `system`)
- optional `ULTRON_STDIO_DEVICE_OWNER_ID`
- optional `ULTRON_STDIO_ALLOWED_DEVICE_IDS` comma-separated
- optional `ULTRON_STDIO_SCOPES`; defaults to `mcp,ultron:tasks,ultron:devices`

The stdio process writes protocol data only to stdout; operational messages go to stderr.

## Tools

- `ultron.submit_task`
- `ultron.get_task`
- `ultron.pause_task`
- `ultron.resume_task`
- `ultron.cancel_task`
- `ultron.list_devices`
- `ultron.device_capabilities`
- `ultron.request_control`
- `ultron.capabilities`
- `ultron.whoami`
- `ultron.health`

`ultron.request_control` is a request surface. A remote agent cannot directly elevate a user's phone or desktop to `MAX_APPROVED`; owner approval remains authoritative.

## Backend modes

### Memory

Default for local development when no remote Task API or bridge mode is selected. Intended for self-tests only; it does not provide durable cloud continuity.

### Durable device bridge

Set production secrets/environment:

- `NODE_ENV=production`
- `ULTRON_BACKEND=bridge`
- `DATABASE_URL` to a PostgreSQL database
- `ULTRON_MCP_TOKENS_JSON`
- `ULTRON_DEVICE_TOKENS_JSON`
- `ULTRON_MCP_HOST=0.0.0.0`
- `ULTRON_MCP_ALLOWED_HOSTS` to the public hostname

Production bridge mode defaults to PostgreSQL. The durable store preserves tasks, device state and idempotency across process restarts and supports safe multi-instance claiming with database locks. The Android device uses `/v1/device/tasks/wait` for bounded long-poll wake, so remote work does not require rapid phone polling.

Real secrets and `DATABASE_URL` must come from the deployment secret store; do not put them in source, CI YAML, documentation examples, or chat transcripts.

### Canonical Task API

Set:

- `ULTRON_BACKEND=http`
- `ULTRON_TASK_API_URL`
- `ULTRON_TASK_API_TOKEN` from the server secret store

The MCP resource server then terminates external MCP authentication and forwards a trusted principal/device scope to the ULTRON Task API. This remains available for deployments where a separate canonical task service is preferred.

## Verification

`npm test` runs the MCP security/routing and in-memory bridge tests. `npm run test:postgres` runs the durable bridge integration suite against a real PostgreSQL database. CircleCI provides PostgreSQL 17 and runs both suites before the MCP job can become green.

The durable suite verifies restart persistence, concurrent idempotent submission, multi-instance no-double-claim behavior, offline queue recovery, capability scoping, pause/resume/cancel, owner-control boundaries and evidence-required terminal success.

## Security invariants

1. Principal identity comes from verified transport auth, never tool arguments.
2. Task ownership is principal-isolated.
3. Device visibility is owner + device-allow-list scoped.
4. Requested capabilities do not grant permissions.
5. Remote agents cannot self-elevate device control.
6. Raw privileged executor primitives are not MCP tools.
7. `MAX_APPROVED` means only capabilities the device owner/OS already granted.
8. Secure OS boundaries, PIN, biometric and CAPTCHA are never bypassed.
9. Production HTTP uses an external authorization/identity system or verified bearer credentials; this server is a resource server, not an authorization server.
