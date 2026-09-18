# Caption Action

**Same Audio. A Brighter World.**

Free, fully local live subtitle overlay for Android. No accounts, no ads, no telemetry, no cloud, no paywall. Offline after the one-time model download.

Package: `com.hatsunama.captionaction`  
minSdk: **26** (Android 8.0) · targetSdk: **34** · Device-agnostic (any modern Android phone)  
Current source version: **0.3.7-projection-fresh** (versionCode 30)

## 0.3.7-projection-fresh

- **Fresh MediaProjection every Start:** never reuse a prior RESULT_OK Intent; always `createScreenCaptureIntent`. Live overlay + `RECORD_AUDIO` checked against phone state **before** the share-one-app / entire-screen UI.
- **After Allow:** FGS starts with explicit `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`; claim failures use `error_projection_claim_failed` (not decline copy); real playback-capture failures use `error_capture` without “allow screen sharing”. `START_NOT_STICKY` so sticky redelivery cannot replay a consumed token.
- **Walkthrough:** projection step is educational only (no early system prompt that wastes a single-use grant). StartHandoffGate / 0.3.6 handoff unchanged. Device-audio-only unchanged.

## 0.3.6-start-handoff

- **Seeker Start handoff:** After model + Allow screen share, the session must stay up (not bounce to Home). `StartHandoffGate` extends a **45 s** suppress window on Start and again on projection grant; `HomeActivity.onResume` never auto-stops while handoff is active **or** the overlay service started within that window. Fixes the race where an 8 s window expired during the system share dialog / `goToLauncherHome` resume.
- **Playback capture fail after Allow:** Toast uses `error_capture` (“Could not capture device playback audio…”) instead of the decline copy; decline still shows dialog + Home (no mic).
- **RESULT_OK (-1):** grant path uses `resultCode != 0 && data != null` consistently with `CaptionOverlayService.start`’s `require(resultCode != 0)`.
- Device-audio-only (0.3.4) unchanged.

## 0.3.5-live-readability

- **Live ZH→EN translation (phrase path):** SenseVoice windows **~2.25 s → ~2.5 s** (`LIVE_WINDOW_SAMPLES=40_000`, min ~32 k) so ML Kit sees fuller phrases. Script-bias stamps `zh`/`ja`/`ko` on CaptionResult when SenseVoice leaves `auto`/blank (decode language stays `auto` — no forced-zh regress on EN audio). Publish/MT only when `AsrJunkFilter.hasEnoughContentForMt` (CJK ≥4 chars; Latin ≥2 tokens / ≥8 letters) — fewer nonsense EN crumbs without expanding particle junk lists. Prefer EN primary: MT wait **600 → 850 ms**; concurrent MT unchanged (no cancel race). Dual honesty unchanged: secondary only when a real translation exists.
- **Limits vs burned-in subs:** Live SenseVoice + on-device ML Kit will not match studio hardsubs. Expect clearer phrase EN and fewer crumb hallucinations; Quality (whisper) remains the accuracy path.
- **Full caption overlay:** default height **280 → 380 px**, type **~10–16 sp** (flatter height scale), primary **32** / secondary **16** maxLines. Bubble **auto-grows** to wrapped content up to ~48% screen (grow-only; drag-resize and geometry persist unchanged). Still **no** `ScrollingMovementMethod` (0.3.1 drag fix preserved). Device-audio-only (0.3.4) unchanged.

## 0.3.4-device-audio

- **Device audio only:** Live/Quality never fall back to the microphone. Declining screen share or failing AudioPlaybackCapture ends the start path with a clear dialog/Toast (“Allow screen sharing to caption device audio”) and returns to Home — no overlay, no mic captions. Pre-Android 10 fails with “requires Android 10+ for device audio”.
- **Logs:** successful live sessions log `captureMode=playback` / `usingPlaybackCapture=true` (never `mic` for product sessions). `RECORD_AUDIO` remains required by Android for playback capture.
- **Overlay readability:** smaller default caption type (~11–22 sp scaled by bubble height), taller default overlay (280 px), more wrap lines (primary 24 / secondary 12). Full captions wrap; drag still works (no ScrollingMovementMethod).

## 0.3.3-accuracy-keepup

- **Live ASR accuracy (Sherpa):** SenseVoice windows raised ~1.0 s → **~2.25 s** (`LIVE_WINDOW_SAMPLES=36_000`, min gates ~28 k) so phrases are not crumb-fragmented before ML Kit. Ultra-short / lone-particle junk (≤2 chars after normalize, 的/了/吧/…) filtered before publish/MT. Honest tradeoff: Live stays faster than Quality but will not match burned-in subs.
- **Quality keep-up (whisper):** Balanced model **ggml-base-q5_1 → ggml-tiny-q5_1** (~31 MB) for faster passes on mid devices. Windows **~1.5 s** normally / **~1.125 s when behind** (overruns/backlog via `setKeepUpBehind`) — still ≥1000 ms native min. Goal: caption updates every ~2–3 s on Samsung-class hardware, not once per 10 s+.
- **EN fast path:** EN target + single-line prefers `language=en, translate=false` (`en-direct`) when recent captions look Latin/EN-heavy; bias falls back to `auto`+translate-to-EN for multilingual sources. Dual / non-EN stay auto + ML Kit.
- **UI honesty:** if no new caption for >~4.5 s while capture is advancing, status → “Still listening — catching up…” and last caption is lightly dimmed (not wiped). Overlay drag / concurrent MT / yeah junk filters unchanged.

## 0.3.2-mt-audio

- **Live MT race (ZH stuck when target EN):** ASR no longer cancels in-flight ML Kit jobs every ~1 s window. Concurrent `enrichWithMt` / `applyPolicy`; UI + recorder apply only when `seq == captionSeq` (logged applied vs skipped).
- **Target-primary preference:** wait ~600 ms for MT before painting source; if ready, show EN/target immediately; on timeout paint source then swap when MT arrives. Dual = target primary + source secondary once MT ready.
- **Subtitle file:** append once per caption seq (final primary), not Chinese then English duplicate.
- **Quality false “No device audio signal”:** do not show no-audio when playback capture has healthy RMS, advancing pump chunks, or queue activity. Overruns / long whisper infer → catching-up / listening (status only); never wipe last good caption with no-audio text.

## 0.3.1-overlay-drag

- **Overlay drag restored:** touching the caption text / bubble moves the window again. Purple handle (or near it) still resizes only. Green stop-dot clicks unchanged.
- Root cause: `ScrollingMovementMethod` on the primary TextView consumed touches so MOVE never fired on most of the box. Fix removes MovementMethod, keeps wrap + no ellipsize (resize the bubble for overflow), and wires the same touch listener on primary/secondary TextViews.
- Geometry persist (`overlayX/Y/W/H`) and stop-dot layout margins unchanged.
- **ASR junk / repeat suppress:** expand `AsrJunkFilter` for SenseVoice/whisper filler loops (`yeah. yeah.`, `oh`, `hmm`, `thank you for watching`, `subscribe`, 嗯/啊/哦, 谢谢观看, …). Overlay consumer skips consecutive-identical captions and near-duplicates within ~2.5 s so Live does not re-paint the same hallucination every window.

## 0.3.0-live-quality

- **Live vs Quality are separate products** on Home (not one buried model picker): Live = Fast/SenseVoice near real-time; Quality = Balanced/Accurate whisper.cpp.
- **Whisper empty ~15 ms root cause:** whisper.cpp rejects audio under 1000 ms (`input is too short`). Quality now drains ~2.5 s windows, pads short PCM, logs `processingTimeMs` / wav size / window RMS / rawLen, and uses unique WAV names.
- **Language path is systemic:** Quality always `language=auto`. `translate=true` only when the subtitle target is English (whisper translate-to-EN). Dual / non-EN stay auto + ML Kit. Removed brittle `en-direct` default that broke Chinese and other non-EN sources.
- Live / Sherpa keeps ~1.0 s windows. Idle overlay still does not wipe real captions; full sentences (no `...`).

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
2. Run on-device ASR (SenseVoice or whisper.cpp)
3. Translate via **ML Kit on-device** when target ≠ spoken/passthrough (optional dual = original + translated)
4. Show a movable, **resizable** overlay above other apps
5. End anytime with the floating **✕** or the green stop-dot
6. Persist settings and overlay geometry locally (DataStore)
7. Optional **Save subtitles to a text file** (Home → Captions): each live session writes a local UTF-8 `.txt` under Documents/captions

**One Home screen** — languages, captions save toggle, overlay font, and status. **Start live captions** (sticky footer) opens the model gate (Fast / Balanced / Accurate), then permissions if needed, then live captions (app minimizes so the overlay sits over other apps).

## Permissions (one at a time)

First Start walks: overlay → device audio access → notifications (API 33+) → screen/audio capture. After the walkthrough you return to Home and tap Start again.

## Model tiers (user-initiated download)

| Tier | Approx size | File | Engine |
|------|-------------|------|--------|
| **Fast** (default) | ~228 MB | `model.int8.onnx` (SenseVoice) | **Sherpa-ONNX** ASR + **ML Kit** MT |
| **Balanced** | ~31 MB | `ggml-tiny-q5_1.bin` | **whisper.cpp** ASR (+ EN fast-path) + **ML Kit** MT |
| **Accurate** | ~182 MB | `ggml-small-q5_1.bin` | **whisper.cpp** ASR (+ EN fast-path) + **ML Kit** MT |

SenseVoice URL: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx  
Tokens (`tokens.txt`) ship in the APK under `assets/sherpa/` and are copied beside the model on first load.

Downloads show progress % with cancel/resume via HTTP Range. Models land in `files/models/`.

## ASR / translation

- **Capture pipeline (0.2.6 → 0.2.7):** dedicated `AudioCapture` pump → bounded PCM queue (~10–12 s, drop-oldest on overrun) → separate ASR consumer. **Never** block `AudioRecord.read` on whisper/Sherpa/MT. Large ~3 s `AudioRecord` buffer. Capture starts only after playback capture succeeds (overlay shown after capture is live).
- **ASR keep-up (0.2.7):** when the queue is behind, `drainToNewestWindow` skips intermediate chunks and feeds a contiguous newest ~2–4 s window so Balanced/Accurate stay live (not sparse). Whisper live threads 2→4. Junk hallucinations (`[BLANK_AUDIO]`, `[Silence]`, `[Music]`, bracket-only) are filtered — never published as captions; status stays Listening until a real caption. Sustained overruns → “Device busy — dropping old audio…”; sustained near-zero RMS → “No device audio signal” (wrong screen / mute mix). Diagnostics: `Log.i("CaptionAction", …)` with pcmMs, queueDepth, overruns, inferMs, filtered, textPreview.
- **Live latency + full captions (0.2.8):** ASR consumer force-flushes each `drainToNewestWindow` PCM (no re-accumulate returning null in 1–15 ms). Playback live min window ~1.25 s (was 2–3 s); drain target ~1.5 s; max keep ~8 s. EN target → whisper `language=en`, `translate=false` (`mode=en-direct`) — skips auto-detect + translate tax on English audio; dual/non-EN stays `auto` + ML Kit (`mode=auto-translate`). Whisper live threads 4→6. Overlay primary caption no longer ellipsizes: wraps many lines inside the bubble (0.3.5: ~10–16 sp, default height 380, up to 32 lines + auto-grow; status line may still ellipsize). Diagnostics include `mode=` + `inferMs`.
- **Pipeline:** PCM queue → `InferenceEngine` (ASR, one pass) → brief MT prefer-wait (~600 ms) or show source then swap → concurrent `MlKitTranslationEngine` (no cancel-on-next-caption; seq guards) → overlay update. Last real caption stays on screen; Listening/catching-up/no-audio are status/spinner only (idle does not wipe captions).
- **Real ASR:** Fast → `SherpaInferenceEngine` (SenseVoice ONNX, accumulated ~3–12 s windows). Balanced/Accurate → `WhisperCppInferenceEngine` (prebuilt whisper.cpp AAR + ggml bins). No demo/stub caption mode.
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
- **inference:** Engines + `InferenceEngineFactory` (Sherpa Fast / whisper.cpp Balanced·Accurate); `MlKitTranslationEngine` owns offline MT + language policy
- **data:** Settings, `ModelCache`, `SubtitleFileRecorder`
- **audio:** Continuous capture pump + bounded PCM queue (**playback capture only** for Live/Quality)

## Privacy

100% on-device inference path. No analytics SDKs. No remote database. Internet is used for optional user-initiated ASR model download and one-time ML Kit translation language packs.

## License

App code: see repository. SenseVoice / sherpa-onnx: follow upstream licenses. Whisper ggml weights: follow whisper.cpp / OpenAI Whisper terms on Hugging Face.
