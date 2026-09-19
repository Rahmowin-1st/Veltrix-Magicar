# Veltrix Magicar

Standalone Android-native AI assistant for the Veltrix Magicar head unit.

## V1

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

The repository includes a production `render.yaml` Blueprint for the durable MCP/device bridge. For this repository, authorize Render's GitHub App for `Rahmowin-1st/Veltrix-Magicar`, then use the Blueprint deploy flow:

[Deploy to Render](https://render.com/deploy?repo=https%3A%2F%2Fgithub.com%2FRahmowin-1st%2FVeltrix-Magicar)

Render prompts for the required secret values instead of storing them in Git. See `mcp-server/PRODUCTION_DEPLOY.md` for the exact secret schemas and evidence-backed acceptance gates.


## V1 RC2 — head-unit assistant runtime

- Local offline **Hey Magicar** keyword spotting while idle; no idle microphone audio is uploaded.
- One-shot assistant lifecycle: wake -> understand -> plan -> act/verify/replan -> final response -> idle.
- Minimal active surface only: pulsing cyan/blue perimeter plus top voice waveform; no chat/buttons over apps.
- Android assistant-role invocation enters the same background session without opening the app UI.
- Assistant keeps listening during an active mission so the user can interrupt or correct it.
- Ordinary reversible UI work can continue without confirmation cards; sensitive/irreversible actions keep an explicit voice confirmation boundary.
- Media playback uses transient ducking while assistant speech remains foreground, with original activation/deactivation chimes.
- Automatic planner fallback/replan budget increased for changed UI and failed steps.
- Cloud Gemini/Groq brain remains available for complex reasoning; deterministic device skills and wake infrastructure remain local.
