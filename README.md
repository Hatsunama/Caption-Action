# Caption Action

**Same Audio. A Brighter World.**

Free, fully local live subtitle overlay for Android. No accounts, no ads, no telemetry, no cloud, no paywall. Offline after the one-time model download.

Package: `com.hatsunama.captionaction`  
minSdk: **26** (Android 8.0) · targetSdk: **34** · Device-agnostic (any modern Android phone)

## Install (promoted)

Paste this short bootstrap in PowerShell (USB debugging on, one arm64 device). It downloads the full installer from GitHub **main**, runs it, then deletes the temp script:

```powershell
$ErrorActionPreference = 'Stop'
$Installer = Join-Path $env:TEMP ("install-caption-action-" + [Guid]::NewGuid().ToString('N') + '.ps1')
try {
  $headers = @{ 'User-Agent' = 'CaptionAction-bootstrap' }
  $token = $env:GH_TOKEN; if (-not $token) { $token = $env:GITHUB_TOKEN }
  if (-not $token) { try { $token = (gh auth token 2>$null) } catch { } }
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/Hatsunama/Caption-Action/main/scripts/install-caption-action.ps1' -Headers $headers -OutFile $Installer
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

1. Capture **sounds playing on the device** (MediaProjection / AudioPlaybackCapture — primary path)
2. Generate live captions on-device
3. Apply language policy (passthrough languages + optional dual subtitles)
4. Show a movable, **resizable** overlay above other apps
5. End anytime with the floating **✕** at the bottom of the screen
6. Persist settings and overlay geometry locally (DataStore)
7. Optional **Save subtitles to a text file** (Home → Captions): when enabled, each live session appends caption lines to a local UTF-8 `.txt` under the app’s Documents/captions folder (`getExternalFilesDir(Documents)/captions/`). One timestamped file per session; flush/close on stop. Opt-in, local only — no upload.

**One Home screen** — languages, captions save toggle, models, and overlay settings are scrollable sections. **Start live captions** stays sticky at the bottom. First Start walks permissions one-at-a-time, then returns to Home; tap Start again to begin (app minimizes so captions appear over other apps).

## Permissions (one at a time)

First Start walks: overlay → device audio access → notifications (API 33+) → screen/audio capture. After the walkthrough you return to Home and tap Start again. No “mic only later” option — this app captions device playback.

## Model tiers (user-initiated download)

| Tier | Approx size | File | Engine |
|------|-------------|------|--------|
| **Fast** (default) | ~228 MB | `model.int8.onnx` (SenseVoice) | **Sherpa-ONNX** real ASR (zh/en/ja/ko/yue) |
| **Balanced** | ~57 MB | `ggml-base-q5_1.bin` | **whisper.cpp** real ASR (ggml) |
| **Accurate** | ~182 MB | `ggml-small-q5_1.bin` | **whisper.cpp** real ASR (ggml) |

SenseVoice URL: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx  
Tokens (`tokens.txt`) ship in the APK under `assets/sherpa/` and are copied beside the model on first load.

Downloads show a dedicated bubble-gum loading UI with progress % and cancel. Interrupted downloads keep a per-tier `.part` file in app-private storage and resume via HTTP Range on the next attempt (if the server ignores Range, the download restarts from scratch). Models land in `files/models/`.

## ASR / translation status (honesty)

- **Architecture:** Full pipeline — audio → `InferenceEngine` → translation policy → subtitle composer → overlay → settings/ring buffer.
- **Real ASR:** Fast → `SherpaInferenceEngine` (SenseVoice ONNX). Balanced/Accurate → `WhisperCppInferenceEngine` (prebuilt whisper.cpp AAR + ggml bins). No demo/stub caption mode.
- **Errors:** If native fails to load or the selected model file is missing/incomplete, Start shows a clear error and does not emit fake captions.
- **Native dependencies:** `app/libs/sherpa-onnx-1.13.8.aar` (~48 MiB) and `app/libs/whisper-android-1.0.0.aar` (~1.1 MiB, arm64-v8a `libwhisper.so` from [ffmpegkit-maintained/whisper v1.0.0](https://github.com/ffmpegkit-maintained/whisper/releases/tag/v1.0.0) / Maven `dev.ffmpegkit-maintained:whisper-android:1.0.0`).
- **English target + whisper:** When `targetLanguage` is `en`, whisper.cpp runs with **translate=true** so non-English speech (e.g. Chinese) becomes English captions.
- **Translation:** Policy-only passthrough + dual UI. Whisper translate covers **EN** target for ggml tiers; Fast SenseVoice has no offline MT. Non-EN targets stay in the spoken language (UI states this honestly).

## Build from source (low memory)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or JDK 17+
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleRelease --no-daemon --max-workers=1
```

`gradle.properties` already sets low-RAM JVM/worker limits. Lint vital may be skipped on OOM; `checkReleaseBuilds` is false.

Release APK: `app/build/outputs/apk/release/app-release.apk`

## Modules

- **ui:** Home + PermissionStep + ThankYou — events/presentation only; start path goes through `LiveCaptionStarter`
- **service:** `LiveCaptionStarter` (start/projection orchestration), `CaptionOverlayService` (session/overlay wiring), `ModelDownloadManager`
- **inference:** Engines + `InferenceEngineFactory` (Sherpa Fast / whisper.cpp Balanced·Accurate); `PassthroughTranslationEngine` is policy only (no fake MT)
- **data:** Settings, `ModelCache` readiness, `SubtitleFileRecorder`
- **audio:** Capture only (playback or mic)

## Privacy

100% on-device inference path. No analytics SDKs. No remote database. Internet is used only for optional user-initiated model download.

## License

App code: see repository. SenseVoice / sherpa-onnx: follow upstream licenses. Whisper ggml weights: follow whisper.cpp / OpenAI Whisper terms on Hugging Face.
