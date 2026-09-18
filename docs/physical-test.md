# Physical device test (device-agnostic)

Works on any Android phone with USB debugging (or wireless debugging). Examples use `adb`; PowerShell-friendly notes included.

## Preferred: promoted release installer

Use the short bootstrap (downloads the full installer, runs it, deletes the temp script):

```powershell
$env:GH_TOKEN = (gh auth token)
.\scripts\bootstrap-install.ps1
```

- Bootstrap: [`scripts/bootstrap-install.ps1`](../scripts/bootstrap-install.ps1)
- Full installer (fetched by bootstrap): [`scripts/install-caption-action.ps1`](../scripts/install-caption-action.ps1)
- Release: https://github.com/Hatsunama/Caption-Action/releases/tags/v0.1.0-mvp
- Package: `com.hatsunama.captionaction`

Only use the debug APK steps below when iterating on a local build.

## 1. Install debug APK (local build / fallback)

**bash / macOS / Linux**

```bash
adb install -r app-debug.apk
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**PowerShell**

```powershell
adb install -r .\app-debug.apk
```

Application id (debug): `com.hatsunama.captionaction.debug`

## 2. Grant permissions

```bash
adb shell pm grant com.hatsunama.captionaction.debug android.permission.RECORD_AUDIO
adb shell pm grant com.hatsunama.captionaction.debug android.permission.POST_NOTIFICATIONS
```

Overlay cannot be granted via `pm grant` on most devices — open the in-app prompt or:

```bash
adb shell appops set com.hatsunama.captionaction.debug SYSTEM_ALERT_WINDOW allow
```

(If `appops` is denied on your OEM, use Settings → Apps → Caption Action → Display over other apps.)

## 3. First-run flow

1. Open **Caption Action** — Home works immediately (no forced setup wizard)
2. Optionally open **Models** / **Languages** / **Overlay** from Home anytime
3. Tap **Start live captions** → one-at-a-time permission screens (overlay → mic → notifications → screen capture)
4. Optionally download a model (dedicated progress UI with cancel)
5. Confirm captions keep updating after the greeting; resize via corner handle; end with bottom ✕

## 4. Live session checks

- Toggle **Start live captions** (Home or Live session — same start path)
- If **Prefer capturing sounds playing on device** is on (default) and Android 10+: system MediaProjection prompt appears
  - **Accept** → playback capture + overlay; app moves to background
  - **Decline / cancel** → captions still start via **microphone fallback**, toast explains playback was declined, app moves to background
- If prefer-playback is **off** (or pre-Android 10): no projection prompt; service starts on mic immediately and minimizes
- Confirm floating overlay appears; drag it; resize via corner handle; leave the app — overlay should remain
- With Fast SenseVoice downloaded, play device audio — real ASR captions should appear (not canned rotating lines)
- Stop via bottom ✕, notification action, or Live session screen
- Kill/reopen app — overlay position/size and language/model settings should restore

## 5. Error paths to verify

| Condition | Expected |
|-----------|----------|
| Overlay denied | Toast / status asking to grant; no crash; PermissionStep does **not** start the foreground service |
| Mic denied | Cannot start capture; clear message |
| Low storage on download | Storage error toast |
| Model missing | Cannot start; model gate only offers download (no demo path); manager shows “not downloaded” / partial resume |
| MediaProjection declined | Toast “playback declined / using microphone”; overlay starts on mic (not a hard fail) |
| Capture unavailable after accept | Service falls back to mic internally, or shows capture error if mic also fails |

## 6. Logs

```bash
adb logcat -s CaptionOverlayService:* AndroidRuntime:E
```

PowerShell: same command inside `adb`.

## 7. Plugging a real ASR engine

**Phase B status:** Sherpa-ONNX AAR is wired. Fast tier downloads SenseVoice `model.int8.onnx`.
`InferenceEngineFactory` returns `SherpaInferenceEngine` when native loads; otherwise null and the session shows an error (no demo fallback).

Remaining / future:
1. Optional whisper.cpp path for Balanced/Accurate ggml bins.
2. Optional streaming (OnlineRecognizer) for lower latency.
3. Offline MT for dual-subtitle translation.

References:
- https://github.com/k2-fsa/sherpa-onnx (Android AAR + SenseVoice)
- https://github.com/ggerganov/whisper.cpp (Android examples / ggml models)


## 8. Uninstall

```bash
adb uninstall com.hatsunama.captionaction.debug
```


## Phase B — Sherpa-ONNX SenseVoice (real ASR)

1. Install debug APK built with `app/libs/sherpa-onnx-1.13.8.aar` (arm64-v8a).
2. On Home → Models / Start gate, select **Fast — SenseVoice** and download (~228 MB). Cancel mid-download, then resume — partial should continue via Range.
3. Start live captions. Session event / logs should show engine `Sherpa-ONNX SenseVoice`.
4. If download skipped or AAR fails to load, Start fails with a clear error — no stub captions.
5. Balanced/Accurate ggml downloads do **not** activate Sherpa (wrong format); they stay for a future whisper.cpp path.
