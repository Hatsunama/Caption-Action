# Isolated unsigned QA identity

Use `-Pcaption.isolatedQa=true` only for the separate QA debug app/test pair:

- App: `com.hatsunama.captionaction.qa`
- Instrumentation: `com.hatsunama.captionaction.qa.test`, targeting the QA app
- Launcher component: `com.hatsunama.captionaction.qa/com.hatsunama.captionaction.ui.home.HomeActivity`

Absent or explicit `false` retains the normal `.debug` identities. Release remains `com.hatsunama.captionaction`; the Kotlin/Java namespace and runtime classes do not change. Invalid property values fail closed. Models, settings, recordings and permissions are separate app-private state, not copied from existing installations.

The existing development CI first runs default-identity unit tests/lints, then explicitly assembles unsigned QA APKs. `verify-qa-identity.py` compares real merged manifests, generated BuildConfig and packaged manifests, checking instrumentation targeting, provider authorities and receiver permissions. QA APKs are staged as `app-qa-unsigned.apk` and `app-qa-androidTest.apk` with identity/hash provenance; raw AGP debug filenames alone do not identify the package. No keys or signing settings are created. Existing signing/device task restrictions still apply.

## Device safety boundary

Do not follow legacy `.debug` install, overwrite or uninstall commands from other test documents for this isolated QA flow. Never remove or overwrite production or existing `.debug` app data. The existing emulator helper hard-codes `.debug` and is not an isolated-QA runner; it remains blocked.

A separately approved device flow must inspect the actual APK identities, signer and installed package/data state before installation. QA artifacts are unsigned until signed on the approved computer with its existing approved key. Do not generate a new key. Identical launcher labels/icons are possible, so use the explicit QA package/component rather than guessing by icon.

Successful host tests, manifests and APK packaging do not establish device/native/ASR/MT acceptance. No model downloads, microphone/camera/capture, permission changes or release approval are implied by this build option.
