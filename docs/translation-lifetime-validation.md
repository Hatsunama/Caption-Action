# ML Kit task and caller lifetime validation

## Scope

This repair follows the transcript-privacy baseline at
`c5acf95199be1b263b9af70251c89bcd6fae1163` (2026-10-12).
It repairs application ownership of ML Kit translator and language-identifier
clients across concurrent requests, timeout, cancellation, replacement and teardown.

Translation policy, language choices, model locations/packs, model-manager download
policy, the 2-second language-ID timeout and 4-second translation timeout are
preserved. No UI redesign, native engine/capture changes, model migration,
signing, artifact assembly, device operation or remote CI is part of this repair.
The prior speech-log redaction and all its assertions remain intact.

## Ownership changes

- A client use is reserved before invoking the SDK. Its lease ends when the actual
  Google Task completes, using an explicit direct-executor completion listener.
  Expiry/cancellation of a coroutine waiter does not mean the SDK operation ended.
- Factory creation is serialized independently of the retirement lock. Release
  does not wait for a blocked factory; a client returned after retirement is
  closed without cache publication or SDK invocation. Concurrent same-pair
  requests cannot leak a losing allocation.
- Retirement is terminal and idempotent. Idle clients close immediately; busy
  clients receive one close attempt after their last SDK Task settles. Release never
  initializes a language-ID client solely to close it.
- Coroutine waiters use the already-present coroutines-play-services bridge and
  propagate cancellation. Existing translation fallback and diagnostic redaction
  are retained.
- Service START retires the previous MT owner. Actual translation deferreds,
  readiness work, and late-result appliers are created lazily, registered with
  generation-checked ownership before starting, and canceled on START/STOP/destroy.
  The readiness error Toast additionally checks owner identity and generation.
- Mic retains its existing structured ASR/translation job and Main publication
  fences; cancellation is propagated instead of converted into a failed result.

Model-manager operations keep their existing waits and timeouts. They are not
translator clients and are not closed or assumed canceled by this repair.
Cancellation checks prevent a canceled readiness caller from starting another model operation
or publishing late readiness status once an outstanding wait returns.

## Regression-first evidence

Tests retain real `MlKitTranslationEngine`, policy, service/activity lifecycle,
caption publication and recorder code. SDK factories and Google Tasks are the
controlled boundaries. No whole-engine ML Kit shadow is used in the new suites.
Actual SDK completion is independently controlled with `TaskCompletionSource`;
blocking factory gates and observed coroutine/task boundaries establish ordering.

Before production edits, `MlKitTranslationLifetimeTest` ran 11 tests with eight
intended failures and three passing controls. Failures demonstrated duplicate
same-pair allocation, release-triggered language-ID initialization, close during
pending translation, close after the unchanged 4s/2s wait timeouts while Tasks
were still pending, creation after release, delayed cancellation, and canceled
language identification cascading into translation. Already-complete Task,
failed Task and synchronous SDK throw controls passed.

Four caller tests then ran against the same preserved production baseline, all
failing at intended ownership assertions: START/STOP did not cancel the actual
SDK-awaiting deferred, and service/activity destruction closed pending clients.
The tests also exercise replacement-client survival, stale caption/status/file
fences, exactly-once closure and real UTF-8 transcript bytes after the fix.

Additional readiness tests block either a real model-manager query Task or
model-manager download Task, replace the service, then fail the old Task. They
check the actual readiness child, no further download, no late Toast/status,
unchanged old/new transcript bytes, and preservation of the replacement owner.
The query case passed on the first repair candidate; the failed-download case
meaningfully failed because a canceled blocking wait still overwrote its owner's
last status. A cancellation check before failure publication repairs that path.

An initial green attempt exposed a test expectation mismatch: the existing
composer wraps primary text at 40 characters and the recorder preserves that
wrapped text. The fixture now compares the complete normalized visible sentence
and exact wrapped view-to-file body, including once-only append. This mismatch
is not counted as an application regression.

The existing privacy tests now inject SDK factories instead of reflecting private
cache/lazy fields; their output and no-content-in-log assertions are unchanged.

## Additional boundary controls and resource diagnosis

Four review-driven controls exercise a throwing factory, a throwing close that
must not block other clients, cancellation of the actual SDK Task, and Task
cleanup overlapping repeated release while SDK close is held at a controlled gate.
They assert application ownership and close attempts, not SDK-internal cleanup.
The exact preserved original engine passed the factory/close-throw controls and
failed the SDK-cancellation propagation and pending-use cleanup assertions.
Repaired engine bytes were restored in a `finally` block and their SHA-256 checked
before further verification.

The first combined run hit its 350-second supervisor limit while in debug tests;
no completed result XML was produced, so it is not counted as a test pass/failure.
An unchanged-source debug retry with per-case logging completed with explicit
`OutOfMemoryError: Metaspace` in the test worker. It reported 190 cases and 14
failures, including resource-induced fixture-entry failures; one class could not
initialize, preventing four normal cases. The earlier daemon memory warning
alone was not treated as proof of that cause.

The retry configuration recycles the sole test worker every four test classes.
It retains the same 512 MiB heap and 384 MiB metaspace ceilings, uses one worker,
and does not alter repository runtime code or assertions to avoid these failures.
Original timeout logs and diagnostic failure XML are retained separately.

## Final verification

Completed 2026-10-12 against the final unsigned-build guard/configuration:

- `testDebugUnitTest`: **198 passed**, zero failures/errors/skips (149 seconds).
- `testReleaseUnitTest`: **198 passed**, zero failures/errors/skips (143 seconds).
- `lintDebug`: passed, zero errors and 103 warnings.
- `lintRelease`: passed, zero errors and 104 warnings (both lint tasks: 96 seconds).
- All **21 new cases** (15 engine, 6 caller) and all **177 existing cases** passed
  in both variants. No test exclusion was used in these final runs.
- Final runtime/test/config SHA-256 values were unchanged across the three stages;
  each bounded supervisor exited successfully with no remaining owned process.
- `git diff --check`: passed.
- Reversing only the privacy test's SDK-injection plumbing exactly reproduces
  its published SHA-256, confirming every other test byte/assertion is unchanged.

The full source checkpoint, manifests, original red/failure logs, final XML,
lint reports and supervisor receipts are retained with the repair evidence.
This does not establish a device/runtime pass.

## Limits and separate work

These checks establish application-side ownership at controlled SDK boundaries.
They do not demonstrate real ML Kit translation quality, model readiness/integrity,
SDK/native crash-freedom, or exact-binary behavior on an Android device. A Task that
never completes must retain its client; a timeout does not make closing it safe.
The earlier privacy-head QA artifact predates this repair and is not relabeled
by these tests. Any separately built signing-validation candidate needs its own
source/artifact receipt and runtime verification.

Home readiness association, Mic duplicate lifecycle, capture terminal errors,
model integrity and retained Sherpa ownership remain separate findings. Existing
ASR, capture, recorder and privacy repairs are preserved and rerun, not claimed
newly repaired here.
