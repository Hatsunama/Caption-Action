# Caption Action

**Same Audio. A Brighter World.**

Free, fully local live subtitle overlay for Android. No accounts, no ads, no telemetry, no cloud, no paywall. Offline after the one-time model download.

Package: `com.hatsunama.captionaction`  
minSdk: **26** (Android 8.0) · targetSdk: **34** · Device-agnostic (any modern Android phone)

## Install (promoted)

Primary install method for the signed MVP release on a physical arm64 Android phone:

1. Install Android platform-tools (`adb` on PATH) and connect one unlocked device with USB debugging authorized.
2. For this private repo, authenticate GitHub before running the installer:

```powershell
$env:GH_TOKEN = (gh auth token)
```

   (Or set `GH_TOKEN` / `GITHUB_TOKEN` another way. The script also tries `gh auth token` itself.)

3. From the repo root (or any clone that includes the script):

```powershell
.\scripts\install-caption-action.ps1
```

The script downloads `caption-action-android.apk` from [v0.1.0-mvp](https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp), verifies the SHA-256 checksum, installs with `adb install -r` (preserves app data), re-enables the package, and launches Home.

Release: https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp  
Script: [`scripts/install-caption-action.ps1`](scripts/install-caption-action.ps1)

## What it does

1. Capture streaming audio (playback capture via MediaProjection when available, microphone fallback)
2. Generate live captions on-device
3. Apply language policy (passthrough languages + optional dual subtitles)
4. Show a movable, resizable overlay above other apps
5. Persist settings and overlay geometry locally (DataStore)

## Model tiers (user-initiated download)

| Tier | Approx size | File | URL |
|------|-------------|------|-----|
| **Fast** | ~31 MB | `ggml-tiny-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin |
| **Balanced** (default) | ~57 MB | `ggml-base-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin |
| **Accurate** | ~182 MB | `ggml-small-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin |

Internal training details are not shown in the UI. Models land in app-private storage (`files/models/`).

## ASR / translation status (MVP honesty)

- **Architecture:** Full pipeline — audio → `InferenceEngine` → translation policy → subtitle composer → overlay → settings/ring buffer.
- **ASR shipped in this MVP:** `DemoInferenceEngine` — fully local timed/energy-gated demo captions so overlay, capture, permissions, and settings can be tested without native libs. Real ggml files can still be downloaded for the future engine.
- **Native path:** `InferenceEngine` + `WhisperCppBridge` / `InferenceEngineFactory` are ready. Recommended plug-ins: **whisper.cpp Android** (loads the same ggml files above) or **Sherpa-ONNX**. See [docs/physical-test.md](docs/physical-test.md).
- **Translation:** Policy engine implements passthrough + dual-subtitle UI. Dedicated offline MT (Marian/OPUS-ONNX) is structured but not bundled yet — when ASR language ≠ target and not in passthrough, text is shown as-is (no cloud MT).

## Build from source (low memory)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or JDK 17+
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug --no-daemon --max-workers=1
```

`gradle.properties` already sets:

```
org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -XX:+HeapDumpOnOutOfMemoryError
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.daemon=false
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Modules

- **UI:** Home, Setup wizard (privacy → permissions → model → languages → mode → preview → start), Live session, Language settings, Model manager, Overlay preview
- **Service:** `CaptionOverlayService` (SYSTEM_ALERT_WINDOW + FGS), audio capture, model download, inference orchestration
- **Data:** DataStore settings, model file cache, in-memory caption ring buffer (cleared on session end)

## Privacy

100% on-device inference path. No analytics SDKs. No remote database. Internet is used only for optional user-initiated model download.

## License

App code: see repository. Whisper ggml weights: follow upstream whisper.cpp / OpenAI Whisper terms on Hugging Face.
