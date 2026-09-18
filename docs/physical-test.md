# Physical device test (device-agnostic)

Works on any Android phone with USB debugging (or wireless debugging). Examples use `adb`; PowerShell-friendly notes included.

Current source: **0.3.1-overlay-drag** — drag anywhere on caption bubble moves overlay; purple handle resizes; stop-dot still works; full captions; junk/repeat filler loops suppressed.

## Preferred: promoted release installer

Use the short bootstrap (downloads the full installer, runs it, deletes the temp script):

```powershell
$env:GH_TOKEN = (gh auth token)
.\scripts\bootstrap-install.ps1
```

- Bootstrap: [`scripts/bootstrap-install.ps1`](../scripts/bootstrap-install.ps1)
- Full installer: [`scripts/install-caption-action.ps1`](../scripts/install-caption-action.ps1)
- Promoted release tag (until superseded): https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp
- Release package: `com.hatsunama.captionaction`

Only use the debug APK steps below when iterating on a local **0.2.3+** build (whisper + SenseVoice + ML Kit MT).

## 1. Install debug APK (local build / fallback)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Application id (debug): `com.hatsunama.captionaction.debug`

## 2. Grant permissions

```bash
adb shell pm grant com.hatsunama.captionaction.debug android.permission.RECORD_AUDIO
adb shell pm grant com.hatsunama.captionaction.debug android.permission.POST_NOTIFICATIONS
adb shell appops set com.hatsunama.captionaction.debug SYSTEM_ALERT_WINDOW allow
```

(If `appops` is denied on your OEM, use Settings → Apps → Caption Action → Display over other apps.)

## 3. First-run flow

1. Open **Caption Action** — single **Home** screen (languages, captions save, overlay font, status)
2. Tap **Live Captions** (Fast/SenseVoice) or **Quality Captions** (Balanced·Accurate whisper.cpp) — separate products on Home
3. Download if needed → one-at-a-time permission walkthrough if required → return to Home → Start again
4. On Android 10+: MediaProjection prompt; decline falls back to mic with a toast; app minimizes
5. Confirm overlay updates on real audio; **drag by grabbing the caption text** to move; resize via purple corner handle only; end with bottom ✕ or green stop-dot

## 4. Live session checks

- Dual switch visible for any **Languages.kt** target (ML Kit MT); hint only if target unsupported
- Target ≠ spoken/passthrough: captions must appear in the **target** language for Fast and Balanced/Accurate (incl. es/fr/zh/ja/etc.)
- Dual on: original + translated when async ML Kit succeeds (whisper is single-pass on live)
- Quality whisper: always language=auto; translate=true only for single-line EN target; dual/non-EN: ASR once + ML Kit
- Filler loops (`yeah. yeah.`, 嗯, thank-you-for-watching) must **not** spam the overlay; identical/near-dup windows suppressed
- Idle silence must keep the last real caption (status/spinner only); must **not** invent captions (no silence-fed ASR)
- Projection revoke mid-session: Toast + session end (no silent mic fallback)
- Home / Start should download MT language packs (status line shows progress); after packs ready, MT works offline
- Stop via ✕, notification action, or returning to Home (Home stops an active session)
- Kill/reopen — overlay geometry and language/model settings restore
- Devices without Google Play services may fail ML Kit pack download (ASR still works)

## 5. Error paths to verify

| Condition | Expected |
|-----------|----------|
| Overlay denied | Clear message; PermissionStep does **not** start FGS |
| Mic denied | Cannot start capture |
| Low storage on download | Storage error toast |
| Model missing / engine load fail / capture fail | Toast + FGS and overlay torn down (not stranded) |
| MediaProjection declined | Toast + mic fallback |
| MediaProjection revoked mid-session | Toast + session end |

## 6. Logs

```bash
adb logcat -s CaptionOverlayService:* AudioCapture:* WhisperCppEngine:* SherpaInferenceEngine:* MlKitTranslation:* AndroidRuntime:E
```

## 7. Engines (current)

- Fast → `SherpaInferenceEngine` (SenseVoice OfflineRecognizer), ~3–12 s accumulated windows
- Balanced/Accurate → `WhisperCppInferenceEngine` (ggml via whisper-android AAR)
- MT → `MlKitTranslationEngine` (ML Kit Translate + language-id when source unknown)
- Factory returns null on native ASR failure — session errors out (no demo fallback)

References:
- https://github.com/k2-fsa/sherpa-onnx
- https://github.com/ggerganov/whisper.cpp
- https://github.com/ffmpegkit-maintained/whisper

## 8. Uninstall

```bash
adb uninstall com.hatsunama.captionaction.debug
# release id:
adb uninstall com.hatsunama.captionaction
```
