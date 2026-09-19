# Veltrix ULTRON MCP — Production Deploy

This service is designed to run independently of the user's laptop. The canonical cloud path is:

`remote agent/MCP client -> public HTTPS MCP/task service -> durable Postgres queue -> Android long-poll bridge -> policy gate -> Android executor -> proof-of-done`

## Required production environment

Set these values in the hosting platform's secret/environment store. Never commit real values to Git.

```text
NODE_ENV=production
ULTRON_BACKEND=bridge
ULTRON_BRIDGE_STORE=postgres
ULTRON_MCP_HOST=0.0.0.0
DATABASE_URL=<Neon/Postgres connection string>
ULTRON_MCP_TOKENS_JSON=<principal bearer-token bindings>
ULTRON_DEVICE_TOKENS_JSON=<device bearer-token bindings>
```

For hosts other than the Render Blueprint, also set `ULTRON_MCP_ALLOWED_HOSTS` to the service's public hostname. The included Render Blueprint derives this at process start from Render's `RENDER_EXTERNAL_HOSTNAME` system variable.

## Secret JSON shapes

Use cryptographically random bearer tokens generated outside source control. The strings shown below are placeholders only. Use a real future Unix timestamp for `expiresAt` and rotate credentials before expiry.

`ULTRON_MCP_TOKENS_JSON`:

```json
{
  "<RANDOM_MCP_BEARER_TOKEN>": {
    "clientId": "owner-client",
    "principalId": "owner",
    "principalKind": "owner",
    "displayName": "Owner",
    "deviceOwnerPrincipalId": "owner",
    "allowedDeviceIds": ["phone-main"],
    "scopes": ["mcp", "ultron:tasks", "ultron:devices", "ultron:control-request"],
    "expiresAt": 1893456000
  }
}
```

`ULTRON_DEVICE_TOKENS_JSON`:

```json
{
  "<RANDOM_DEVICE_BEARER_TOKEN>": {
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

The timestamp above is an example schema value, not a recommended permanent expiry. Choose an appropriate future expiry and rotate both bindings periodically.

Do not add raw shell or unrestricted low-level executor capabilities to external agent scopes. Android policy and OS/user grants remain authoritative even when a remote principal requests a capability.

## Render Blueprint

The repository root contains `render.yaml`. It creates a Node web service from `mcp-server`, waits for linked CI checks before auto-deploying, runs `/health` checks, and prompts for the three secret values (`DATABASE_URL`, MCP token bindings, device token bindings) during initial Blueprint creation.

Render free services can spin down during inactivity. This is safe for task durability because mission/device state lives in Postgres, not the process filesystem. When the Android bridge or an agent reconnects, the service can cold-start and continue from durable state.

## Docker

`mcp-server/Dockerfile` provides a host-agnostic production image. Supply the same environment variables at runtime. The image runs as the non-root Node user and includes an HTTP health check.

## Android pairing

1. Deploy the service and obtain its HTTPS base URL.
2. In ULTRON -> Control -> Remote Agent Link, enter the HTTPS base URL and the device bearer token that corresponds to this phone.
3. ULTRON stores the token through Android Keystore-backed storage and does not display it again.
4. Select Veltrix as the Android assistant and grant only the OS capabilities you actually want it to use.
5. Remote missions then arrive through the battery-conscious long-poll bridge and still pass through normal permission/policy/verification gates.

## Acceptance checks

Before considering a deployment verified:

- `GET /health` returns HTTP 200 and reports the durable bridge ready.
- An unauthenticated MCP/task request is rejected.
- A valid scoped principal can submit a task.
- A task survives a service restart.
- A matching phone heartbeat causes queued work to become claimable.
- A remote task that needs permission becomes `WAITING_FOR_USER` on Android.
- Cancel/pause/resume propagate correctly.
- `VERIFIED_DONE` is rejected without evidence.
- A successful real-device mission returns evidence to the remote task receipt.

The last item requires an actual Android runtime/device and is not satisfied by compile-only or server-only CI.
