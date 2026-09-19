# Caption Action

**Same Audio. A Brighter World.**

Free, fully local live subtitle overlay for Android. No accounts, no ads, no telemetry, no cloud, no paywall. Offline after the one-time model download.

Package: `com.hatsunama.captionaction`  
minSdk: **26** (Android 8.0) · targetSdk: **34** · Device-agnostic (any modern Android phone)  
Current source version: **0.3.14** (versionCode 37)

## Install (promoted release)

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
} finally { if (Test-Path -LiteralPath $Installer) { Remove-Item -LiteralPath $Installer -Force } }
```

Or run the checked-in bootstrap from a clone:

```powershell
.\scripts\bootstrap-install.ps1
```

The bootstrap pulls [`scripts/install-caption-action.ps1`](scripts/install-caption-action.ps1), which downloads `caption-action-android.apk` from the promoted GitHub release tag ([v0.1.0-mvp](https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp) until a newer release is published), verifies SHA-256, installs with `adb install -r`, and launches **Home**. For local iteration, install `app/build/outputs/apk/debug/app-debug.apk` instead (debug id `com.hatsunama.captionaction.debug`).

For this private repo, authenticate first (`$env:GH_TOKEN = (gh auth token)`).

## What it does

1. Capture **sounds playing on the device** (MediaProjection / AudioPlaybackCapture **only** — no microphone fallback). Declining screen share cancels start with a clear message. Captions use internal playback capture and work **independent of speaker volume** when projection is granted (volume can be muted).
2. Run on-device ASR (whisper.cpp Live/Quality; optional SenseVoice)
3. Translate via **ML Kit on-device** when target ≠ spoken/passthrough (optional dual = original + translated)
4. Show a movable, **resizable** overlay above other apps
5. End anytime with the green stop-dot
6. Persist settings and overlay geometry locally (DataStore)
7. Optional **Save subtitles to a text file** (Home → Captions): each live session writes a local UTF-8 `.txt` under Documents/captions

**One Home screen** — languages, captions save toggle, overlay font, and status. **Live** / **Quality** CTAs open the model gate (Live = Balanced whisper Tiny default + optional SenseVoice; Quality = Balanced / Accurate), then permissions if needed, then captions (app minimizes so the overlay sits over other apps).

## Permissions (one at a time)

First Start walks: overlay → device audio access → notifications (API 33+) → screen/audio capture. After the walkthrough you return to Home and tap Start again.

## Model tiers (user-initiated download)

| Tier | Approx size | File | Engine |
|------|-------------|------|--------|
| **Balanced** (Live default) | ~31 MB | `ggml-tiny-q5_1.bin` | **whisper.cpp** ASR (`language=auto`, EN fast-path) + **ML Kit** MT |
| **Accurate** (Quality) | ~182 MB | `ggml-small-q5_1.bin` | **whisper.cpp** ASR (+ EN fast-path) + **ML Kit** MT |
| **SenseVoice** (optional) | ~228 MB | `model.int8.onnx` | **Sherpa-ONNX** ASR (zh/en/ja/ko/yue) + **ML Kit** MT |

SenseVoice URL: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx  
Tokens (`tokens.txt`) ship in the APK under `assets/sherpa/` and are copied beside the model on first load.

Downloads show progress % with cancel/resume via HTTP Range. Models land in `files/models/`.

## ASR / translation

- **Capture pipeline (0.2.6 → 0.2.7):** dedicated `AudioCapture` pump → bounded PCM queue (~10–12 s, drop-oldest on overrun) → separate ASR consumer. **Never** block `AudioRecord.read` on whisper/Sherpa/MT. Large ~3 s `AudioRecord` buffer. Capture starts only after playback capture succeeds (overlay shown after capture is live).
- **ASR keep-up (0.2.7):** when the queue is behind, `drainToNewestWindow` skips intermediate chunks and feeds a contiguous newest ~2–4 s window so Balanced/Accurate stay live (not sparse). Whisper live threads 2→4. Junk hallucinations (`[BLANK_AUDIO]`, `[Silence]`, `[Music]`, bracket-only) are filtered — never published as captions; status stays Listening until a real caption. Sustained overruns → “Device busy — dropping old audio…”; sustained near-zero RMS → “No device audio signal” (wrong screen / mute mix). Diagnostics: `Log.i("CaptionAction", …)` with pcmMs, queueDepth, overruns, inferMs, filtered, textPreview.
- **Live latency + full captions (0.2.8):** ASR consumer force-flushes each `drainToNewestWindow` PCM (no re-accumulate returning null in 1–15 ms). Playback live min window ~1.25 s (was 2–3 s); drain target ~1.5 s; max keep ~8 s. EN target → whisper `language=en`, `translate=false` (`mode=en-direct`) — skips auto-detect + translate tax on English audio; dual/non-EN stays `auto` + ML Kit (`mode=auto-translate`). Whisper live threads 4→6. Overlay primary caption no longer ellipsizes: wraps many lines inside the bubble (0.3.5: ~10–16 sp, default height 380, up to 32 lines + auto-grow; status line may still ellipsize). Diagnostics include `mode=` + `inferMs`.
- **Pipeline:** PCM queue → `InferenceEngine` (ASR, one pass) → brief MT prefer-wait (~600 ms) or show source then swap → concurrent `MlKitTranslationEngine` (no cancel-on-next-caption; seq guards) → overlay update. Last real caption stays on screen; Listening/catching-up/no-audio are status/spinner only (idle does not wipe captions).
- **Real ASR:** Live default / Quality → `WhisperCppInferenceEngine` (prebuilt whisper.cpp AAR + ggml bins, `language=auto`). Optional Fast → `SherpaInferenceEngine` (SenseVoice ONNX, ~3 s windows, zh/en/ja/ko/yue). No demo/stub caption mode.
- **Offline MT:** Google ML Kit on-device Translate for the `Languages.kt` set. Packs prepare in the background — **Start does not wait**. Live hot path uses short MT timeouts (no 120 s `Tasks.await` downloads on the ASR path).
- **Whisper live path:** **one pass only**. Dual / non-EN → ASR once + async ML Kit. Single-line EN (no dual) → one `translate=true` pass. No blocking dual two-pass whisper on live.
- **MediaProjection:** held on `CaptionOverlayService` with `registerCallback`; `onStop` → visible `failSession` Toast; decline/capture-fail → dialog/Toast + Home (no mic); projection stopped on teardown.
- **Dual:** original + translated when MT supplies `translatedText`. Home dual switch enabled for supported targets.
- **Session errors:** model-missing / engine-null / load-fail / capture-fail / projection-stopped toast and tear down FGS + overlay. MT pack failure toast; ASR still runs.
- **Native / Play Services:** `app/libs/sherpa-onnx-1.13.8.aar` and `app/libs/whisper-android-1.0.0.aar` (arm64-v8a). ML Kit typically needs Google Play services.

## Build from source (low memory)

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or JDK 17+
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug --no-daemon --max-workers=1
# or: ./gradlew assembleRelease --no-daemon --max-workers=1
```

`gradle.properties` already sets low-RAM JVM/worker limits.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`  
Release APK: `app/build/outputs/apk/release/app-release.apk`

## Modules

- **ui:** Home + PermissionStep + ThankYou — presentation only; start path goes through `LiveCaptionStarter` (projection grant required; decline → dialog on Home)
- **service:** `LiveCaptionStarter` (start/projection/fail-home), `CaptionOverlayService` (playback-only session/overlay), `ModelDownloadManager`
- **inference:** Engines + `InferenceEngineFactory` (whisper.cpp Live/Quality; optional Sherpa SenseVoice); `MlKitTranslationEngine` owns offline MT + language policy
- **data:** Settings, `ModelCache`, `SubtitleFileRecorder`
- **audio:** Continuous capture pump + bounded PCM queue (**playback capture only** for Live/Quality)

## Privacy

100% on-device inference path. No analytics SDKs. No remote database. Internet is used for optional user-initiated ASR model download and one-time ML Kit translation language packs.

## License

App code: see repository. SenseVoice / sherpa-onnx: follow upstream licenses. Whisper ggml weights: follow whisper.cpp / OpenAI Whisper terms on Hugging Face.
