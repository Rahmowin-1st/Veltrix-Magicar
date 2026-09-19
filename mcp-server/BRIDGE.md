# Veltrix ULTRON MCP Device Bridge

`ULTRON_BACKEND=bridge` runs one authenticated service that exposes both the MCP objective surface and the canonical task/device bridge APIs.

## Security boundaries

- MCP principals authenticate with `ULTRON_MCP_TOKENS_JSON`.
- Devices authenticate separately with `ULTRON_DEVICE_TOKENS_JSON`.
- Device identity and owner identity come from the token binding, never request JSON.
- A device-reported capability becomes effective only when it is available, OS/user-granted, and present in that device token's `allowedCapabilities` scope.
- Remote MCP principals never receive raw tap/type/shell executor tools.
- `VERIFIED_DONE` from a device requires evidence.
- Android remains authoritative for per-action permission, owner approval, policy and local proof-of-done.
- Secrets belong in the deployment secret/environment store only; never commit real bearer tokens or database credentials.

## Production durable bridge

Production bridge mode defaults to the Postgres-backed store so queued missions, idempotency, device presence and task state survive service restarts and work across multiple cloud instances.

```text
NODE_ENV=production
ULTRON_BACKEND=bridge
DATABASE_URL=<postgres connection string from secret store>
ULTRON_MCP_HOST=0.0.0.0
ULTRON_MCP_ALLOWED_HOSTS=<public-hostname>
ULTRON_MCP_TOKENS_JSON=<secret JSON>
ULTRON_DEVICE_TOKENS_JSON=<secret JSON>
```

`ULTRON_BRIDGE_STORE=postgres` may be set explicitly. Production in-memory bridge mode is rejected by default; the `ULTRON_ALLOW_IN_MEMORY_BRIDGE=true` escape hatch exists only for deliberate emergency/dev use and is not the normal deployment path.

The durable bridge uses database locking for multi-instance task claiming and preserves `(principalId, idempotencyKey)` uniqueness across restarts and concurrent submissions.

Example device binding shape using placeholders only:

```json
{
  "<DEVICE_BEARER_TOKEN_FROM_SECRET_STORE>": {
    "deviceId": "phone-main",
    "ownerPrincipalId": "owner",
    "kind": "PHONE",
    "displayName": "Main Android",
    "allowedCapabilities": ["OPEN_APP", "UI_CLICK", "UI_TYPE", "UI_SCROLL"],
    "controlProfile": "ASK_EACH_ACTION",
    "expiresAt": 1893456000
  }
}
```

## Agent/task routes

- `POST /v1/tasks`
- `GET /v1/tasks/:taskId`
- `POST /v1/tasks/:taskId/pause`
- `POST /v1/tasks/:taskId/resume`
- `POST /v1/tasks/:taskId/cancel`
- `GET /v1/devices`
- `GET /v1/devices/:deviceId`
- `POST /v1/devices/:deviceId/control-requests`
- `GET /v1/capabilities`
- `/mcp`

## Device routes

- `POST /v1/device/heartbeat`
- `POST /v1/device/tasks/claim`
- `POST /v1/device/tasks/wait`
- `GET /v1/device/tasks/:taskId`
- `POST /v1/device/tasks/:taskId/state`

The Android assistant uses the bounded wait route for battery-conscious remote wake instead of rapid polling. The wait request is capped at 30 seconds; the current Android runtime uses a 25-second wait and exponential retry backoff on transport failures.

A device must heartbeat before claiming work. A task with no matching online device remains durably queued and is assigned when an authorized matching device later heartbeats. This lets cloud-side missions survive laptop shutdown and resume phone execution when the phone returns online.

## CI acceptance

CircleCI runs the normal MCP security/routing tests plus a real PostgreSQL 17 integration test. The durable integration test verifies restart persistence, concurrent idempotency, multi-instance no-double-claim behavior, capability scoping, pause/resume/cancel, owner-control boundaries and the proof-of-done evidence requirement.
