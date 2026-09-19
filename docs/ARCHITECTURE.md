# Veltrix Ultron Architecture

## Product split

1. **Android Layer** — ambient assistant, overlay, Accessibility executor, Live, local memory, permission UI, checkpoints.
2. **Cloud Brain** — task routing, provider adapters, cloud-safe missions, agent gateway, push/resume.

A powered-off phone cannot execute phone UI actions. Cloud-safe work may continue and device-bound steps remain checkpointed until the phone reconnects.

## Mission contract

Every mission has objective, source identity, constraints, completion criteria, plan, checkpoints, evidence, and current state.

Canonical lifecycle:

`Understand -> Tell -> Permission -> Execute -> Observe -> Recover -> Verify -> Follow-up`

## Authority

- User instructions outrank the active plan.
- Authorized agent instructions are scoped by delegated permissions.
- Agents never receive raw credentials.
- Model output never directly owns unrestricted device control.
- Passwords/secrets live behind a local encrypted vault and explicit secret policy.

## Executor preference

`Deterministic skill / Intent / API -> accessibility semantic nodes -> browser DOM -> vision -> gesture fallback`

Coordinates alone are never the primary skill representation.

## Brain router

Provider-independent. 9Router and direct providers are adapters behind a Veltrix-owned capability router. Routing considers task capability, health, latency, context, cost policy, and confidence.

## Interruption

`Pause`, `Stop`, `Cancel`, `Undo`, `Take over`, and `Resume` are first-class commands. Long operations checkpoint before handoff whenever technically possible.

## Proof of done

An action is not complete because a tap was emitted. The executor must observe an expected post-condition or return a precise unverified/blocked state.
