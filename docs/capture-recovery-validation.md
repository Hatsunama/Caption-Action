# Capture failure recovery and Mic publication lifetime

## Defects and repair

Mic's previous Activity-wide duplicate fields survived listening restarts and language-pair changes. Exact matches were rejected before the existing 1,500 ms utterance gap was checked, so identical speech could disappear indefinitely. The fields were also updated before the cancellable Main publication, allowing unpublished text to affect later decisions.

Publication state now belongs to a single listening run. Main checks the captured source/target pair and elapsed gap before suppressing an identical phrase, publishes the ASR line, and only then records that publication. A new pair applies on the next window and appends independently. The duration uses `SystemClock.elapsedRealtime()` so wall-clock corrections cannot lengthen or shorten the utterance window. Diagnostic wall-clock measurements are unchanged. Growing-phrase revisions and the 80-line limit remain intact. ASR appears before translation; failed MT keeps that source and does not clear duplicate state to force an every-window retry. A later utterance can translate the same phrase again, and a late successful translation still replaces its own ASR line.

AudioCapture previously ended its pump after a negative read or exception without retiring/stopping its AudioRecord or notifying a caller. Both callers could silently exit their drains while retaining listening UI/resources; Live could encounter this before model preparation finished. A terminal read now schedules one independent Main notification. Before retiring capture, it verifies the same current session and active scope. Stop/release retain the existing stop-before-next-allocation and in-flight-read lifetime rules. The caller callback runs outside both capture monitors and never joins its own pump. Mic checks its listening-run identity and uses its existing stop/error path. Live checks its START generation and uses its existing `failSession(error_capture)` cleanup, including cancellation of held preparation and actual pending MT work. Live remains playback-only.

Independent review then identified the adjacent pre-existing MediaProjection callback race: an old `onStop` could queue failure on Main, then tear down a replacement START. The callback now captures its claim generation and rechecks generation, projection identity and teardown on Main immediately before the existing failure path. Current projection failure still cleans up; already queued duplicate callbacks cannot repeat error navigation.

## Regression boundaries

The controlled AudioRecord tests now distinguish genuine errors from their former synthetic EOF convention. Buffered-PCM tests cancel only the pump Job while a zero-result sentinel read is held, then let it return. This preserves queued samples/remainders and all existing assertions; a public stop would incorrectly clear the evidence. Actual negative results and thrown reads must report once without any drain, stop before release, and invoke the callback outside capture locks. Additional controls cover queued/stale errors across replacement, cancellation, zero reads, zero-energy PCM and ordinary stop.

Actual Activity clicks/NumberPicker callbacks cover 1,499/1,500 ms repeats, restarts, both pair changes, cancelled Main publication, source-first MT failure/new-utterance retry, late replacement, growing revisions and bounded history. Actual service START and model loading are held at existing external boundaries to prove immediate failure cleanup and rejection of late old callbacks. SDK TaskCompletionSource proves canceled MT completion cannot overwrite retained source or transcript bytes.

Only one existing privacy fixture needed plumbing changes: its reflected duplicate seed now targets the current run after the real start is held at a controlled drain. All prior synthetic inputs and assertions are preserved; reversing that plumbing and the added no-op failure overload reconstructs its original SHA-256 exactly.

## Verification

- Baseline runtime preserved and hashed before changes.
- First focused run: 35 cases, 13 failures, zero errors/skips. Twelve matched the defects; one revealed that the test clock had not instrumented Mic's wall-clock call.
- Corrected test-only instrumentation: 35 cases, 12 intended failures, zero errors/skips. All 16 original capture cases, silence/cancellation/replacement controls, and Mic revision/80-line control passed.
- Separate elapsed-clock baseline: eight Mic cases, seven intended failures, zero errors/skips. Its controlled elapsed clock advances while the instrumented wall clock stays fixed.
- First repaired focused run: 36 cases, zero failures/errors/skips.
- Isolated wall-clock-only counterfactual: eight Mic cases, one failure, precisely the elapsed-versus-wall-clock assertion. Repaired monotonic bytes were restored in a finally block and SHA-256 verified.
- Capture/Mic checkpoint: 218 tests in each debug/release variant, zero failures/errors/skips; both lints passed and all source hashes stayed unchanged. This checkpoint is archived separately from the later projection guard.
- Projection callback baseline: seven actual caller cases, two intended failures, zero errors/skips. An old queued callback retired its replacement, and two queued current callbacks repeated the error navigation.
- Projection guard focused green: seven actual caller cases, zero failures/errors/skips.
- Final complete verification including the projection guard: **220 passed in each debug/release variant**, zero failures/errors/skips; both lints passed (zero errors, 103 debug / 104 release warnings). All runtime/test source hashes stayed unchanged across the three stages. The inherited ML Kit, privacy, capture, recorder and lifecycle regressions are included without exclusions.
- Final `testUnsignedBuildGuard`, `assembleDebug` and `assembleDebugAndroidTest`: passed, including 28 guard fixtures and zero forbidden graph tasks. Both APKs passed structural and real-apksigner unsigned gates; package identities remain `com.hatsunama.captionaction.debug` and `.debug.test`. Build input source hashes stayed unchanged. No device execution or signing is implied.
- Final development APK SHA-256: `0cc0151cfc871b5403f4252dbe823a3c8783d936296d7bfaaef58039eef02643`; instrumentation APK: `04ef05978e4a3cfbb92dd77c03287151d8fb90c96dbc3ee43c7ccfd5ba6dbbdb`.

Raw source hashes, before/after source, owned-process logs, JUnit XML and fixture-invariance receipt are retained with the local validation evidence. No emulator/physical device, model download, external publication, signing or credentials were used. These tests prove application behavior at controlled hardware/native/SDK boundaries, not device AudioRecord behavior, real translation quality or native inference safety.
