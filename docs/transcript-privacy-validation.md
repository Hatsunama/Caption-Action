# Transcript diagnostic privacy repair

## Scope

The 2026-10-12 read-only audit found recognized and translated speech in application
logs even when subtitle recording was disabled. This repair changes diagnostic
payloads only. Whisper, optional Sherpa, shared junk filtering, ML Kit policy and
explicit translation, Live, and Mic now report lengths, decisions, language labels,
sequence IDs, timings, and error types instead of speech fragments.

Speech-processing failures also avoid logging throwable messages/stack traces,
which can echo the SDK input. Model preparation, capture, and other errors retain
their existing behavior. Caption rendering, explicit transcript recording, ASR/MT
policy, cancellation, resource ownership, timeouts, and model selection are not
changed. No speech hashes or reversible encodings replace the removed content.

The cascade review also found and removed a content-bearing final-script-gate
message in Live's `publishCaption`, in addition to the audit's enumerated sites.

## Meaningful red tests

Tests were added and run against unchanged production code before source edits.
The Android `ShadowLog` sink captured actual production diagnostic calls, including
exception payloads. Sixteen targeted cases demonstrated content leaks:

- Real Whisper engine and shipped Java wrapper, with only JNI replaced; returned
  caption text was checked before asserting its absence from logs.
- Shared junk-filter rejection, both application tags.
- Real ML Kit translation policy and explicit translation bodies, with cached SDK
  translator boundaries returning synthetic completed tasks; source and translated
  outputs were checked, including crumb rejection and a speech-echoing failure.
- Retained Sherpa engine rejection with recognizer/stream boundaries controlled.
- Real Live service accepted caption with recording disabled, duplicate and short
  same/wrong-script decisions, and quick/late translation publication.
- Real Mic activity accepted caption, junk rejection, and wrong-script rejection.

Live/Mic tests control capture, inference, settings, and translation boundaries;
they do not mock the consumer or publisher. Separate real-engine tests exercise
translation internals. The late-MT UI assertion preserves the existing composer's
source-plus-translation output; this repair does not alter that behavior.

Early fixture runs included SDK initialization and UI line-wrapping expectation
failures. Those are not counted as evidence of the product defect. Final red
artifacts record 15 content-assertion failures; the corrected late-MT rerun records
the sixteenth content-assertion failure, before any production source edit.

## Verification boundary

Full offline JVM debug/release and lint results are recorded below.
Only already installed dependencies and SDKs are used, with one Gradle worker,
512 MiB test heap and 384 MiB test metaspace. No native build, APK assembly,
installation, model download, connected-device operation, signing, GitHub write,
or CI action is part of this repair.

These are application-log privacy regressions, not a claim of real ASR/MT quality,
exact APK/device verification, public logcat accessibility, remote exfiltration,
or universal suppression of diagnostics inside third-party native/SDK binaries.
The separately audited ML Kit lifetime, Home readiness, Mic duplicate lifecycle,
capture terminal-error, model-integrity, and Sherpa ownership issues remain open.

## Initial completed checks (2026-10-12)

- `testDebugUnitTest`: 169 tests, zero failures/errors/skips.
- `testReleaseUnitTest`: 169 tests, zero failures/errors/skips.
- `lintDebug`: passed, zero errors, 103 warnings.
- `lintRelease`: passed, zero errors, 104 warnings.
- `git diff --check`: passed.
- Six-file before/after comparison: all non-diagnostic lines unchanged, except
  removal of the now-unused preview local. All former speech-bearing diagnostic
  fields are absent. This source check supplements the executable log assertions.

The combined offline command completed successfully in 4m 29s. It used the existing
local Gradle launcher, `--offline`, one worker, in-process Kotlin compilation, and
explicit test memory limits. Gradle warned about daemon metaspace pressure and
future Gradle-9 deprecations but completed; lint is not warning-free.

Reproduce the relevant tasks with the repository's existing Android toolchain:

```
./gradlew --offline --no-parallel --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
```

On memory-constrained hosts, bound both Gradle and test-worker memory; this run
used a 384 MiB Gradle heap / 320 MiB metaspace and a 512 MiB test heap / 384 MiB
metaspace, with two active processors and one test fork.

## Independent-review hardening

A follow-up review found no additional proven application-log leak, but identified
coverage gaps. Tests now inspect every captured message plus attached throwable
stack traces (including nested causes), consistently across engine, Sherpa, Live,
and Mic assertions.

Eight additional regressions cover:

- Whisper transcription failure: null fallback and temporary WAV cleanup remain.
- Sherpa decode failure with speech in a nested cause: null fallback and exactly
  one stream release remain.
- ML Kit language-identification failure: original source remains, without MT.
- Live policy failure and Mic explicit-MT failure: existing source-display
  fallback remains. Live currently composes the source twice through its two
  fallback publications; this test records that existing behavior, not a repair
  or endorsement of it.
- Mic near-duplicate suppression: short filler drift remains suppressed.
- Mic's defensive meta/music check and Live's final script gate: rejected content
  remains off the UI. The shared sanitizer and CaptionDisplay normally reject the
  same content first, so these two tests deliberately bypass only that preceding
  helper to exercise the defensive guard independently. They do not claim those
  later branches are naturally reachable with the current helpers.

For controlled fail-before verification, all six runtime files were temporarily
restored to the original published bytes. Each original Git blob was checked
against the published-base manifest first; current repaired bytes were separately
frozen. A try/finally runner restored all six repaired files and verified their
SHA-256 values before final green checks. No new production edit was needed.
The controlled original-source run executed 40 tests: all 24 privacy tests failed
specifically on log-content assertions, while 16 existing ownership tests passed;
there were zero errors or skipped tests. Early fixture errors are retained as
history and are not counted as privacy evidence.

### Final hardened checks (2026-10-12)

- Debug: 177 tests, zero failures/errors/skips.
- Release: 177 tests, zero failures/errors/skips.
- Both lint variants passed with zero errors (103/104 warnings).
- Combined offline command: `BUILD SUCCESSFUL in 3m 53s`.
- All six runtime files still match the reviewed repaired SHA-256 values exactly.
- `git diff --check` passed. No production changes were made during hardening.

These results supersede the initial 169-test results above. Native/device/APK
verification and the separately documented behavior/lifetime issues remain open.
