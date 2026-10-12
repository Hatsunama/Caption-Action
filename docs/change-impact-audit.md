# Change impact and selective cascading audit

Baseline: `1e06a516c4a8f42c6ec51c16da660837ec6584c6`. This report separates
implemented, specifically requested repairs from new audit-only findings. A
passing test is bounded evidence, never proof that all behavior is correct.

## Implemented repairs and actual failure evidence

### A. Live playback windows (medium; original defect reproduced)

Path: `AudioCapture.drainToNewestWindow` → `CaptionOverlayService.runAsrConsumer`
→ Whisper/Sherpa inference → caption policy/publishing. The pump continues to
feed its bounded PCM queue independently. Mic uses `drainFifoWindow` instead.

Original behavior fills the oldest batch and stops before consuming queued newer
chunks. A non-aligned final chunk is treated as backlog and its leading samples
are trimmed even with a healthy producer. New Live-only helper takes a bounded
snapshot of queued backlog and returns the newest exact contiguous window; normal
overshoot is retained for the following call. Reset occurs at pump start/stop.

Mechanically extracted original `takeChunk` and `drainToNewestWindow` production
bodies were executed against the same assertions as the current production wrapper
and real helper. Queue/hardware boundaries are fixtures; source bodies were not
replaced with a separately invented algorithm.

- Backlog regression: expected samples 30–47; baseline instead starts at sample 6.
- Ordinary non-aligned stream: expected all 80 samples; baseline loses 18.
- Aligned and short final-window controls are included.
- Combined audio/dedup run: baseline 5 tests / 3 failures; current same 5 / 0 failures.
- Mic `drainFifoWindow`, `concatFifo` and `trimFifoBacklogIfNeeded` bodies were
  compared with baseline and are byte-identical.

Unit coverage also tests bounded catch-up while new chunks arrive, variable target
sizes, reset, short final flush and invalid target. These fixtures do not validate
real AudioRecord thread interleavings or end-to-end latency/recognition quality.

### B. Live exact-phrase suppression (medium; original defect reproduced)

Path: ASR result → `AsrJunkFilter.normalize` → duplicate decision → short-source
policy or translation → `publishCaption` → subtitle append. Baseline exact equality
has no expiry while near equality does. A later valid repeated phrase stays blocked.

The original exact predicate was mechanically extracted and tested at a 59-second
pause: baseline suppresses; current gate accepts. New gate gives both exact/near
matches a 2.5-second window from the last accepted publication, not the last rejected
candidate. Each consumer has fresh state; elapsed time uses a monotonic clock.
Growing phrases still use the existing `isNearDuplicate` policy. Other ASR and
translation decisions are unchanged.

Tests cover expiry boundary, repeated rejected candidates, new session, near matches,
extensions, blank input and clock rollback. Actual audio pauses, translation ordering,
recorded transcript output and app lifecycle require device/integration checks.

### C. Model gate/download ownership (medium; original defects reproduced)

Path: Home Live/Mic entry → shared `showModelGateThenStart` → user-initiated download
→ cache validation → settings write → `onReady` → permission/capture flow.

- Each dialog owns its session/job/downloader. Cancel, Back, outside dismissal,
  reopen and destruction invalidate it and cancel its work.
- Queued progress and late readiness require live session, showing dialog and exact
  current dialog identity. Successful readiness is one-shot.
- Cancellation is permanent for each manager attempt, closes active HTTP, retains
  coroutine cancellation, and serializes all managers per canonical destination.

Four production-path tests compile the **entire original/current** downloader,
cache and model-tier source. Only Android Context.filesDir and the HTTP connection
are fakes; real temp files, output streams and coroutines execute. Home's actual
Use-button coroutine body is mechanically extracted with a suspended settings write.
All four fail before and pass after:
1. Dismiss during suspended settings write: original calls late `onReady`.
2. Cancel before download starts: original clears cancellation and opens network.
3. Overlap two managers: original opens two output streams for the same partial file.
4. Download-success callback suspended in settings update also delivers late readiness.

Additional helper tests cover double completion, reopen, disconnect once, cancelled
waiter, canonical file locking and retry after failure. They do not execute actual
Android AlertDialog delivery, rotation/process death, real HTTP stack blocking, or
MediaProjection permission UI. Those are explicitly unverified.

## Release/API preparation is separate behavior-affecting work

Release signing no longer silently selects the debug key. Separate unsigned AAB/APK
staging paths preserve ID and version, with no credential reads. API 36 requires
AGP/Gradle updates and system-window handling. These changes can affect layout and
platform lifecycle behavior; they are not compression or a claim of identical
rendering. Screenshot and affected-feature verification on both phones is mandatory.
No compression/dependency/model migration is applied; see [audit plan](compression-audit.md).

## New findings: audit only, deduplicated

| ID / severity | Evidence and affected path | Status / smallest proposed repair | Required verification |
|---|---|---|---|
| N1 medium | `MicTranslatorActivity.kt:416`: exact equality precedes silence-gap calculation at 422. State at 64–65 is not reset by start/stop at 304–335. | Source-confirmed sibling of B, left unchanged. Bound equality by utterance gap and reset session-only dedup state while preserving displayed history and FIFO. | Repeated phrase after silence and off/on fails before/pass after; growing revision, near match, saved history and From/To unaffected; actual Mic on both phones. |
| N2 high | `CaptionOverlayService.stopSelfSafe` cancels ASR then calls engine.release. `WhisperCppInferenceEngine.kt:109` locks model lookup only; native transcribe at 294 runs outside the release lock at 316. Official v1.0.0 wrapper/JNI has no inference/release mutex and release calls `whisper_free`. | Code-evident concurrent lifetime risk, no crash reproduced. Do not infer coroutine cancellation stops synchronous native inference. Proposed single-owner/serialized engine lifetime: delay release until in-flight native work completes, without blocking main UI; guard generation/result publication. | Controlled in-flight inference/stop/restart race test before/after, no release-before-completion, double release, failed native call, cancellation/recovery; both-phone stop during long ASR and repeated model use. |
| N3 medium | `AudioCapture.kt:305,481` cancels pump without join; blocking read at 318 may return and enqueue at 322 after a new queue/session is created. | Potential capture-generation race; not runtime-proven. Proposed generation/record ownership guard and orderly old-pump termination. | Barrier-based old-read/new-start test, no stale samples after restart, microphone and projection modes, timeout/error recovery. |
| N4 medium | Pump read error at `AudioCapture.kt:334-336` exits; takeChunk at 351 eventually returns null; Live consumer at 438 exits without explicit stop/error UI. Mic shares the stop condition. | Source-visible recovery gap, actual device effect untested. Proposed explicit terminal capture reason propagated to owning UI/service. | Inject negative read result, verify meaningful error and clean teardown/retry, never a silently dead listening state. |
| N5 medium | Downloader accepts HTTP206 without validating Content-Range offset; ModelCache readiness uses only >=90% approximate bytes. | Pre-existing integrity/resume risk, separate from C; left unchanged. Proposed strict resume range/length validation and verified model integrity, retain resumable recovery. | Incorrect/missing range, truncated body above threshold, interrupted resume, changed upstream entity, insufficient storage and retry. No corruption event is claimed. |

N2 official source: [wrapper v1.0.0](https://github.com/ffmpegkit-maintained/whisper/blob/97fbac037e3a64a6e12d0efd8fa964ad6e249d87/library/src/main/kotlin/dev/ffmpegkit/whisper/Whisper.kt)
and [JNI](https://github.com/ffmpegkit-maintained/whisper/blob/97fbac037e3a64a6e12d0efd8fa964ad6e249d87/library/src/main/jni/whisper_jni.cpp).

## Native compatibility qualification

Every baseline arm64 library has 16 KiB PT_LOAD alignment. Five fail the literal
current Android GNU_RELRO end-modulus check; Whisper itself passes. However both
bundled libc++ and official NDK28c libc++ place writable data in a subsequent LOAD
beyond the rounded protection end, so the corruption/crash scenario described by
the guide is **not proven** by that modulus alone. No runtime replacement or native
upgrade was applied. Authoritative linker interpretation and actual 16 KiB tests
remain open. [Official guidance](https://developer.android.com/guide/practices/page-sizes#check-relro).

## Scope, gaps, and release gate

Inspected: live and Mic consumers, capture pump/windowing/FIFO/reset, duplicate/filter
policy, caption publication/subtitle append, download manager/cache, gate callbacks,
settings continuation, service teardown, Whisper wrapper/native lifetime and build
configuration. This is selective follow-up around the changed paths, not an exhaustive
application security or correctness audit.

Verified: focused helper tests, extracted production fail-before/pass-after checks,
Gradle configuration/task discovery, diff check, static package/ELF inspection.
Not completed: declared Android test environment, full lint/debug/release builds,
actual APK/AAB baseline measurements, Android UI/network instrumentation, device ASR/MT,
16 KiB runtime and signing. Earlier tool cancellations interrupted validation; authorized SDK setup resumes
after confirmation they were not intentional.

**Release remains blocked.** Exact final artifacts must cold-start and pass affected
feature checks on **both connected phones**, after the final source/build change.
No physical access is available to this task. Do not infer cold-start/feature success
from unit tests; do not recommend install, publish or release until the gate is met.
