# Veltrix Magicar V1 release gate

Current software line: **1.0.0-rc2**.

V1 must not be called fully finished until both software and physical head-unit gates pass.

## Automated software gate

The main CI must pass all of the following on the same commit:

- Android debug APK build
- Android release APK build
- Android unit tests
- offline Hey Magicar model provisioning
- MCP server install, typecheck and full self-test suite

The normal CI uploads both debug and unsigned release APK artifacts.

## Signed production APK

Run the **Veltrix Magicar Signed Release** workflow only after these GitHub Actions secrets exist:

- `VELTRIX_ANDROID_KEYSTORE_BASE64`
- `VELTRIX_ANDROID_KEYSTORE_PASSWORD`
- `VELTRIX_ANDROID_KEY_ALIAS`
- `VELTRIX_ANDROID_KEY_PASSWORD`

The keystore is decoded only into the runner temporary directory. It must never be committed to Git.

## Physical Magicar acceptance

A real head-unit run is still mandatory before V1.0.0 final:

1. install the signed APK on the Magicar head unit;
2. grant microphone, overlay, accessibility and assistant-role permissions;
3. verify boot and ACC-on startup;
4. verify idle local `Hey Magicar` wake without cloud audio upload;
5. verify the cyan/blue perimeter and top waveform at 1280x720 without blocking touch;
6. verify interruption/barge-in while a mission is running;
7. verify app open/click/type/scroll actions and automatic replan after a changed UI;
8. verify sensitive/irreversible actions stop for explicit confirmation;
9. verify media ducks during assistant speech and restores afterward;
10. verify the assistant returns to idle and does not self-initiate actions;
11. verify cloud Gemini/Groq path and backend task bridge on the real device;
12. run a sustained ignition/session test for wake reliability, memory pressure and thermal stability.

Only after these pass should `versionName` become `1.0.0` and `versionCode` become `3`.
