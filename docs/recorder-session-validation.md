# Recorder session preservation

## Contract and root cause

README's optional caption-save feature promises a local UTF-8 text file for each live session. `startSession` already closes the prior session, `stopSession` returns the saved path, and `discard` closes without deleting output. Repeated sessions must not destroy previously saved captions, even when wall-clock time repeats or separate recorder objects share a directory.

The baseline recorder at `6ecc3377d10b5ce32aa41c1fa7ca4735bd513cb5` derives the entire filename from a whole-second timestamp and opens it with `FileOutputStream(..., false)`. Starting another session in that second truncates the earlier transcript. An object-local lock cannot protect other recorder instances or existing files.

## Maintained tests

`SubtitleFileRecorderSessionTest` invokes the real recorder against isolated real filesystem directories. It verifies all UTF-8 bytes, including headers, Unicode, line separators and previous sessions after each operation. An internal clock seam fixes time deterministically while the public constructor continues to use system time.

- Rapid stop/start preserves both complete sessions.
- Starting while active flushes and preserves the replaced session.
- Concurrent independent recorders own distinct files.
- A failed replacement closes the old session, reports inactive/null path, preserves output and allows recovery.
- Internal storage fallback, blank/duplicate secondary handling, repeated stop/discard and append outside an active session preserve exact output.
- Seeded cumulative sequences interleave start, replace, append, stop and discard; every retained file is checked after every operation.

The sequence seeds are reproducibility aids, not a numerical coverage target. Each scenario has observable file-content and lifecycle assertions.

## Lightweight reproduction

Run `bash scripts/regression-evidence/recorder/run.sh` with `GRADLE_HOME` pointing at an unpacked Gradle distribution. This uses its Kotlin/JUnit jars and compiles the entire before/after recorder source. The original source is read from the pinned Git commit, with only clock injection added. The same maintained test file supplies every assertion; preparation removes only the Robolectric runner/configuration and adapts the Android Java getter to the Kotlin filesystem fixture property.

This harness is bounded production-recorder evidence. It is not an Android lifecycle, storage permission, native inference, media projection, live-audio or physical-device test. The actual Robolectric tests and repository-wide Android checks must be reported separately.

## Deliberate boundaries

No UI, Hatsu animation, native inference, model selection or translation implementation changes belong to this repair. Overlay pending/late-translation behavior and the separately identified native lifetime/session races require their own production-boundary reproductions. No service-orchestrator or device-validation claim is implied by these filesystem tests.

## Executed evidence (2026-10-12)

The guarded standalone first run, before the filename repair, ran all six tests against both the pinned baseline and the clock-seam-only working source: each had five failures. The retained first session changed from 148 UTF-8 bytes to 136 bytes on restart; concurrent sessions all returned the same path. The repair changes allocation to `File.createTempFile` in the caption directory, atomically reserving a unique timestamp-prefixed `.txt` file. It does not rely on a higher-resolution clock or a process-local counter.

The same guarded harness then reported baseline six tests/five failures and fixed six tests/zero failures. The guard verified no live owned descendants remained. Standalone tests used JDK 17 and the Kotlin 2.0.21 compiler/JUnit jars bundled with Gradle 8.13. Android builds use the project's Kotlin plugin version and are a separate validation stage.

Adjacent audit-only limitations: a header-write exception can occur before the writer is assigned to the recorder, and a flush exception in `closeUnlocked` prevents its subsequent close call. Append failures are currently logged without changing `isRecording`. These paths need dedicated fault-injection reproductions and explicit expected recovery behavior before repair; this batch does not claim write-error or descriptor-lifetime coverage.

## Android verification status

The full requested Android check command was:

```
:app:compileDebugKotlin :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug
```

No AAB or release packaging was requested or generated. The initial three attempts stopped before tests on cloud setup problems: inherited proxy configuration, a read-only Android preferences path, and conflicting modern/legacy Android home variables. Those settings were corrected outside the repository.

Later full-check executions became inaccessible through their execution-session status interface before a terminal result or cleanup summary could be retrieved. The persisted logs stop during configuration/pre-build. Accordingly, full Android compilation, Robolectric tests, lint and the development APK are **unverified**, not passed. No device, emulator, audio or live-capture validation occurred. The successful standalone regression evidence above does not substitute for those checks.

The maintained test's final changes after the successful standalone run add an explicit plain Android Application runner setting, and optional copies of the two already-verified transcript files for reviewers. The same six assertions remain, but this final runner/copy configuration still needs execution. The harness additionally requires the known first-transcript data-loss assertion in the baseline log, so a generic baseline crash cannot count as a reproduction.
