# Veltrix Magicar

Private Android-native AI execution layer and task gateway.

## V0

- Android-native Compose command center
- mission contracts and lifecycle
- scoped permission engine
- agent task contract
- Accessibility executor foundation
- AI chat/personal memory UI foundation
- CI build + unit tests

## Core invariant

`Understand -> Tell -> Permission -> Execute -> Observe -> Verify -> Follow-up`

User or authorized-agent interruption always outranks the active mission plan.

## Cloud bridge deploy

The repository includes a production `render.yaml` Blueprint for the durable MCP/device bridge. For this private repository, authorize Render's GitHub App for `Rahmowin-1st/Veltrix-Magicar`, then use the Blueprint deploy flow:

[Deploy to Render](https://render.com/deploy?repo=https%3A%2F%2Fgithub.com%2FRahmowin-1st%2FVeltrix-Ultron)

Render prompts for the required secret values instead of storing them in Git. See `mcp-server/PRODUCTION_DEPLOY.md` for the exact secret schemas and evidence-backed acceptance gates.
