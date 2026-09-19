# Veltrix ULTRON Universal Fabric V1

## North star

ULTRON is a universal execution fabric, not a phone-only assistant and not a raw remote-control API.

A user, AI, agent, tool, workflow, cloud service, phone, or desktop submits an **objective**. ULTRON decides where and how to execute it, applies policy, observes the target environment, executes permitted actions, adapts, verifies, and returns evidence.

## Canonical topology

```text
User / AI / Agent / Tool / Workflow
             |
      Dedicated ULTRON MCP
       HTTPS Task API / A2A
             |
      Authenticated Principal
             |
       Universal Task Gateway
             |
        AI Planner / Router
             |
       Device + Policy Router
       /        |        \
 Android     Desktop     Cloud
 executor    companion   executor
       \        |        /
      Observe -> Execute -> Verify
             |
          Evidence
```

## Interfaces

### 1. Native Task API — source of truth

All integrations terminate into the same task semantics:

- submit task
- get task
- pause task
- resume task
- cancel task
- capabilities
- list devices
- device capabilities
- request control profile

The API is objective-based. Remote callers do not receive direct privileged Android/desktop executor handles.

### 2. Dedicated ULTRON MCP

The dedicated MCP is the preferred tool surface for ChatGPT, Codex, Claude, custom agents, IDE agents and other MCP-capable systems.

Canonical tools:

- `ultron.submit_task`
- `ultron.get_task`
- `ultron.pause_task`
- `ultron.resume_task`
- `ultron.cancel_task`
- `ultron.list_devices`
- `ultron.device_capabilities`
- `ultron.request_control`
- `ultron.capabilities`

MCP authentication establishes the principal. Tool arguments cannot select a different principal identity.

### 3. Phone and desktop device fabric

A device is enrolled to an owner and reports:

- kind: phone / desktop / cloud
- platform: Android / Windows / macOS / Linux / web
- online/offline state
- available capabilities
- capabilities actually granted by the owner/OS
- control profile

A task can target a specific device, phone, desktop, cloud, or AUTO. AUTO selects a currently online device that has all required granted capabilities and is within the authenticated device scope.

## Control profiles

- **READ_ONLY** — observation only.
- **ASK_EACH_ACTION** — mutating actions require approval.
- **MAX_APPROVED** — ULTRON may use all capabilities the owner has already granted on that device without repetitive prompts for the same allowed scope.

`MAX_APPROVED` never creates capabilities that the OS/user has not granted and never bypasses PIN, biometric, CAPTCHA, secure screens, or platform security boundaries.

## One-flow device setup

The product UX exposes a guided **Connect + Enable Max Approved** setup.

On Android the flow guides through system-owned assistant, Accessibility, overlay, notification and background availability screens. On desktop the companion guides through pairing, screen/input control, chosen filesystem scope and background service setup.

ULTRON may reduce navigation to one guided flow, but OS-required user confirmations remain user-controlled.

## Security invariants

1. Principal identity comes from transport authentication, not task arguments.
2. Requested capabilities are advisory and never grant permission.
3. Only a device owner can directly enable or elevate a control profile.
4. Remote agents can request control elevation, but owner approval is required.
5. Device routing is restricted to the authenticated owner/device allow-list.
6. Task reads and mutations are principal-isolated.
7. Raw device executor primitives stay behind ULTRON policy and verification boundaries.
8. DONE requires evidence.
9. Offline devices do not receive surprise queued privileged actions unless the mission and permission model explicitly permit resume.
10. A global kill switch can stop execution.

## Planned implementation sequence

1. Universal device registry and capability discovery.
2. Dedicated MCP task surface.
3. Android adapter into the universal executor endpoint.
4. Windows desktop companion first; macOS/Linux adapters follow the same contract.
5. Cloud gateway + durable task queue + push resume.
6. AI Planner converts natural objectives into dynamic action graphs.
7. Screen-aware re-planning and recovery.
8. Evidence packs and cross-device resume.
9. A2A adapter for agent-to-agent delegation where useful.
