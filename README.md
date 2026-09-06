# Caption Action

**Same Audio. A Brighter World.**

Free, fully local live subtitle overlay for Android. No accounts, no ads, no telemetry, no cloud, no paywall. Offline after the one-time model download.

Package: `com.hatsunama.captionaction`  
minSdk: **26** (Android 8.0) · targetSdk: **34** · Device-agnostic (any modern Android phone)

## Install (promoted)

Paste this short bootstrap in PowerShell (USB debugging on, one arm64 device). It downloads the full installer from GitHub, runs it, then deletes the temp script:

```powershell
$ErrorActionPreference = 'Stop'
$Installer = Join-Path $env:TEMP ("install-caption-action-" + [Guid]::NewGuid().ToString('N') + '.ps1')
try {
  $headers = @{ 'User-Agent' = 'CaptionAction-bootstrap' }
  $token = $env:GH_TOKEN; if (-not $token) { $token = $env:GITHUB_TOKEN }
  if (-not $token) { try { $token = (gh auth token 2>$null) } catch { } }
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/Hatsunama/Caption-Action/feature/android-mvp/scripts/install-caption-action.ps1' -Headers $headers -OutFile $Installer
  & $Installer
}
finally { if (Test-Path -LiteralPath $Installer) { Remove-Item -LiteralPath $Installer -Force } }
```

Or run the checked-in bootstrap from a clone:

```powershell
.\scripts\bootstrap-install.ps1
```

The bootstrap pulls [`scripts/install-caption-action.ps1`](scripts/install-caption-action.ps1), which downloads `caption-action-android.apk` from [v0.1.0-mvp](https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp), verifies SHA-256, installs with `adb install -r`, and launches Home.

For this private repo, authenticate first (`$env:GH_TOKEN = (gh auth token)`).

Release: https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp

## What it does

1. Capture streaming audio (playback capture via MediaProjection when available, microphone fallback)
2. Generate live captions on-device
3. Apply language policy (passthrough languages + optional dual subtitles)
4. Show a movable, **resizable** overlay above other apps (corner handle; text scales with box size)
5. End anytime with the floating **✕** at the bottom of the screen (stops MediaProjection / overlay service)
6. Persist settings and overlay geometry locally (DataStore)

Home opens immediately — **no forced setup wizard**. Models, languages, passthrough, dual mode, fonts, and permissions are all available anytime from Home / Live session.

## Permissions (one at a time)

Starting live captions walks a friendly explanation screen **before each** system dialog, in order: overlay → microphone → notifications (API 33+) → screen/audio capture. No Settings + mic popup pile-ups.

## Model tiers (user-initiated download)

| Tier | Approx size | File | URL |
|------|-------------|------|-----|
| **Fast** | ~31 MB | `ggml-tiny-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin |
| **Balanced** (default) | ~57 MB | `ggml-base-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin |
| **Accurate** | ~182 MB | `ggml-small-q5_1.bin` | https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin |

Downloads show a dedicated bubble-gum loading UI with progress % and cancel. Models land in app-private storage (`files/models/`).

## ASR / translation status (MVP honesty)

- **Architecture:** Full pipeline — audio → `InferenceEngine` → translation policy → subtitle composer → overlay → settings/ring buffer.
- **ASR shipped in this MVP:** `DemoInferenceEngine` — continuous local timed/energy-aware demo captions (keeps updating after the greeting). Real ggml files can still be downloaded for the future engine.
- **Native path:** `InferenceEngine` + `WhisperCppBridge` / `InferenceEngineFactory` are ready. Recommended plug-ins: **whisper.cpp Android** or **Sherpa-ONNX**. See [docs/physical-test.md](docs/physical-test.md).
- **Translation:** Policy engine implements passthrough + dual-subtitle UI. Demo lines include sample translations so dual mode visibly changes. Dedicated offline MT is not bundled yet.

## Build from source (low memory)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or JDK 17+
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleRelease --no-daemon --max-workers=1
```

`gradle.properties` already sets low-RAM JVM/worker limits. Lint vital may be skipped on OOM; `checkReleaseBuilds` is false.

Release APK: `app/build/outputs/apk/release/app-release.apk`

## Modules

- **UI:** Home (bubble-gum pop), Live session, sequential Permission steps, Language settings, Model manager (loading UI), Overlay preview (drag + freeform resize)
- **Service:** `CaptionOverlayService` (SYSTEM_ALERT_WINDOW + FGS + bottom ✕ + resize handle), audio capture, model download, inference orchestration
- **Data:** DataStore settings, model file cache, in-memory caption ring buffer (cleared on session end)

## Privacy

100% on-device inference path. No analytics SDKs. No remote database. Internet is used only for optional user-initiated model download.

## License

App code: see repository. Whisper ggml weights: follow upstream whisper.cpp / OpenAI Whisper terms on Hugging Face.
