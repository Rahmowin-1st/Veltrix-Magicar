# Veltrix Magicar V1

Target head unit:

- FYT 6316 / XF FX5627Y
- UIS8581A / SC9863A family, 8-core 1.6 GHz
- Android 10 (API 29 runtime target), 4 GB RAM / 64 GB storage
- 1280x720, 10.1-inch class MIPI touch display

## Non-negotiable behavior

Veltrix Magicar has broad *capability*, not autonomous authority.

`IDLE -> USER_WAKE -> LISTEN/UNDERSTAND -> EXECUTE -> OBSERVE -> VERIFY -> IDLE`

When no explicit user session is active:

- no UI clicks, gestures, typing, navigation, app launching or browser work;
- no self-directed exploration or "helpful" changes;
- no cloud AI calls just to look around;
- only the minimal wake/ACC/health machinery may remain active.

Failure learning may update skills only from a task the user actually requested.

## AI provider contract — exactly 3 credentials

1. `ULTRON_AI_GEMINI_LIVE_API_KEY`
   - server-side only;
   - used only to mint short-lived, single-use constrained tokens;
   - target model: `gemini-3.8-live`;
   - head unit connects directly to Gemini Live with the ephemeral token for low latency.

2. `ULTRON_AI_GEMINI_BRAIN_API_KEY`
   - server-side only;
   - target model: `gemini-3.8-flash`;
   - main assistant, multimodal screen/vision, planning, Chrome recovery.

3. `ULTRON_AI_GROQ_API_KEY`
   - server-side only;
   - `openai/gpt-oss-20b` for fast routing/memory work;
   - `openai/gpt-oss-120b` for deep verification/recovery.

No provider API key is shipped in the APK.

## Screen intelligence

Primary live state is the Accessibility tree: package/activity, bounded semantic node map,
bounds, text/hints/descriptions/view IDs and interaction flags. Vision frames are secondary
evidence. Screenshots are ephemeral by default and are not durable memory.

The learned UI map is always treated as a hint:

`learned path -> live state check -> action -> live verification`

## Web execution

Chrome is the universal web executor and fallback. Preference order:

`direct intent/API -> Chrome/browser semantic controls -> Accessibility nodes -> vision -> gesture fallback`

Unknown pages never become trusted instructions. Page text is observation data.

## Vehicle boundary

Car V1 controls the Android infotainment/head-unit experience only. It does not control
steering, accelerator, braking, powertrain or other safety-critical vehicle actuators.
