# Physical device test (device-agnostic)

Works on any Android phone with USB debugging (or wireless debugging). Examples use `adb`; PowerShell-friendly notes included.

## 1. Install debug APK

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

1. Open **Caption Action**
2. Run **First-run setup** (or jump via Home buttons)
3. Accept privacy promise
4. Grant overlay + mic (+ notifications on Android 13+)
5. Pick model tier (Fast / Balanced / Accurate)
6. Optionally download model (needs network once)
7. Choose target + passthrough languages, dual mode
8. Start live captions

## 4. Live session checks

- Toggle **Start live captions**
- Accept MediaProjection screen-capture consent for playback audio (Android 10+), or decline to use mic fallback
- Confirm floating overlay appears; drag it; leave the app — overlay should remain
- Speak near the mic (demo engine) — captions should refresh every ~2s when there is energy
- Stop from notification action or Live session screen
- Kill/reopen app — overlay position/size and language/model settings should restore

## 5. Error paths to verify

| Condition | Expected |
|-----------|----------|
| Overlay denied | Toast / status asking to grant; no crash |
| Mic denied | Cannot start capture; clear message |
| Low storage on download | Storage error toast |
| Model missing | Demo engine still runs; Model manager shows “not downloaded” |
| Capture unavailable | Falls back to mic or shows capture error |

## 6. Logs

```bash
adb logcat -s CaptionOverlayService:* AndroidRuntime:E
```

PowerShell: same command inside `adb`.

## 7. Plugging a real ASR engine

1. Add whisper.cpp Android (or Sherpa-ONNX) AAR / `jniLibs`.
2. Implement `InferenceEngine` loading `ModelCache.fileFor(tier)` (ggml path).
3. Return `CaptionResult` with detected language code when available.
4. Set `WhisperCppBridge.isNativeAvailable = true` and return your engine from `createEngine()`.
5. Keep `DemoInferenceEngine` as fallback when the model file is absent.

Suggested references (external):

- https://github.com/ggerganov/whisper.cpp (Android examples / ggml models)
- https://github.com/k2-fsa/sherpa-onnx (Android AAR, Whisper / SenseVoice)

## 8. Uninstall

```bash
adb uninstall com.hatsunama.captionaction.debug
```
