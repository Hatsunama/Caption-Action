# Session lifetime and output ownership validation

## Scope and baseline

Validation date: 2026-10-12. This change extends the recorder-session repair at
[`8fcdee2977c0e58cd6b2251ae4320448a86a4179`](https://github.com/Hatsunama/Caption-Action/commit/8fcdee2977c0e58cd6b2251ae4320448a86a4179).
It preserves the previously published atomic unique-file allocation, recorder
tests, development checks and emulator evidence handling.

The product repair is limited to native Whisper model ownership, capture-session
ownership, and the microphone/activity and overlay/service handoffs that publish
engines and caption output. Model selection, model files and inference/translation
quality are outside this change.

## Ownership changes

- `WhisperCppInferenceEngine` leases each native resource while in use. Release
  retires it immediately, prevents new use and schedules exactly-once cleanup
  after the final native user finishes. Inference is serialized. Stale or
  cancelled loads retain cleanup ownership, and loading preserves the configured
  source language.
- `AudioCapture` gives each session its own record, queue, window drainer,
  remainder and counters. Pumps and drains retain their originating session;
  retired or cancelled work cannot publish into its replacement. Record release
  waits for both platform stop and pump completion. Failed starts release their
  unpublished allocation. Cancellation checks preserve uncommitted FIFO
  remainder and stop inactive callers from entering a fresh session.
- `MicTranslatorActivity` and `CaptionOverlayService` retain preparation
  ownership across cancellation and dispatcher handoffs. The service cancels
  prior ASR at START, fences publication and caption/UI state by generation, and
  releases replaced engines.
- `SubtitleFileRecorder` adds path-checked append and stop operations while
  retaining existing generic methods and unique-file creation. Service file
  transitions are serialized on IO; stale appends and delayed cleanup must match
  the active path, protecting replacement transcripts.

## Test-first evidence

Tests execute production method bodies with controlled external boundaries.
Whisper tests use the shipped AAR's Kotlin wrapper/model bodies and shadow only
JNI methods and library loading. A blocking, non-cancellable native-call fixture
ensures that coroutine cancellation cannot falsely stand in for native completion.
Capture tests shadow hardware allocation/read/start/stop/release while retaining
production capture, queue and windowing behavior. Caller/output tests retain real
recorder bodies and assert actual UTF-8 transcript bytes.

- Native lifetime: five initial assertions failed on the unchanged engine,
  demonstrating free during native use, cancelled-load leaks, stale load
  publication and source-language reset. A later counterfactual independently
  confirmed the eventual-cleanup assertion. The corrected serialization fixture
  observed two simultaneous native users on the baseline where one was required.
- Caller preparation: two intended failures among ten tests demonstrated stale
  preparation replacing a newer engine and failure to release a replaced engine.
- Caller/output handoff: the final sixteen-test file ran before output repairs;
  six product assertions failed, ten controls passed and there were no errors.
  Failures covered old ASR cancellation, a delayed recorder open after STOP,
  stale append bytes in a replacement file, disabled recording retaining a
  writer, composer text crossing recording sessions, and retired inference
  advancing caption state.
- Capture ownership: the same thirteen core tests produced eight product
  assertion failures and five passing controls on preserved original capture
  code, then passed after repair. Three additional cancellation tests first
  failed on the initial repaired candidate, then passed after the narrow
  cancellation/remainder correction.
- The two direct path-ownership API tests are positive controls, not claimed
  baseline failures. The six existing recorder tests are preserved and rerun.

Fixture initialization failures, worker-starvation/watchdog attempts and
superseded harness errors are excluded from product-regression claims. Original
failure evidence and corrected before/after results were retained separately.

## Final targeted verification

The final combined debug-unit selection passed exactly **48 tests**, with zero
failures or errors:

- `WhisperCppLifetimeTest`: 8
- `CallerPreparationOwnershipTest`: 16
- `ExpectedRecorderSessionOwnershipTest`: 2
- `SubtitleFileRecorderSessionTest`: 6
- `AudioCaptureSessionTest`: 16

This selection runs through `:app:testDebugUnitTest` with those five `--tests`
filters. Production and test source hashes were checked after the later full
verification stages; no source changes occurred between the resource-limited
unit attempt and its retry.

## Full host verification

- Unfiltered `:app:testDebugUnitTest`: **153 passed**, zero failures/errors/skips.
- Unfiltered `:app:testReleaseUnitTest`: **153 passed**, zero failures/errors/skips.
- `:app:lintDebug`: **0 errors, 102 warnings**.
- `:app:lintRelease`: **0 errors, 103 warnings**.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest`: **passed**.

The first unfiltered attempt executed all 153 debug tests but two UI tests failed
with `OutOfMemoryError: Metaspace`; release did not execute. Both original UI
failures and the original logs/XML were preserved. The successful retry changed
only a local test-worker metaspace ceiling from **192 MiB to 384 MiB**; heap
remained **512 MiB** with one worker. This retry did not change repository build
settings. The initial failures are not reclassified as product assertion failures
or a passing full-suite run.

Lint was compared with the hosted reports for the exact published baseline
commit above, which had 108 warnings in each variant. After normalizing checkout
roots and ignoring shifted line numbers, there were **no new warning IDs and no
new source-code warnings**. The only new warning message was the existing
dependency-advisory rule reporting Robolectric 4.17 for unchanged configured
4.16. Removed version advisories explain the lower counts; this is not a
warning-free claim or a claim that product changes fixed those advisories.

## Separate CI behavior change

The experimental emulator job is now a **manual-only, default-false opt-in**.
Its automatic PR-branch trigger was removed after the prior bounded CPU-only
startup failure. Ordinary development/release checks and opted-in emulator
runtime/failure capture remain unchanged.

After this separately reviewed adjustment, static workflow checks passed
**12/12**, emulator-helper fixtures **7/7**, orientation/resource checks **7/7**,
and shell-syntax/diff checks passed. These are static and controlled-fixture
results; they do not establish a successful emulator boot or device test.
See [development CI validation](development-ci-validation.md) for its scope.

## Remaining gates and limits

- Real ARM64 native execution, same-binary ASR/translation behavior, microphone
  and playback capture, MediaProjection lifecycle, and both-phone end-to-end
  output remain pending. Host shadows and successful assembly do not prove them.
- Platform `AudioRecord.stop` can still block on an OEM implementation. A native
  read that never returns retains its retired record rather than freeing an
  in-use allocation. No timeout-based unsafe free is claimed.
- Existing synchronous STOP/destroy file closing, broader ML Kit client lifetime
  and recorder writer-error/close fault injection remain outside this repair.
- Exact-head hosted CI must be checked after any publication. These local
  results do not imply merge, installation, production signing or release
  approval. No release APK/AAB was generated by this development verification.

Raw local logs/XML, build caches, model/user content, signing material and local
build overrides are excluded from this documentation and repository change.
