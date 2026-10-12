# Development-only and exact-commit CI validation

## Test-first repair

The previous workflow at baseline `6ecc3377d10b5ce32aa41c1fa7ca4735bd513cb5` created a Play AAB and an unsigned release APK on every run. It also used the default PR merge checkout rather than the reviewed candidate head. A configuration regression was written and failed before those workflow edits. The final assertions fail against the original workflow and pass against the candidate. A separate emulator-boundary check first failed because no guarded execution script existed, then passed with physical and mixed-device targets proven to stop before Gradle.

Independent review found that an early failed Gradle pipeline prevented phase-specific XML capture, and ABI rejection occurred before the terminal-evidence trap was installed. Controlled shell fixtures reproduced both failures before repair. The helper now records Gradle and logging exit codes separately, copies available raw phase results (or an explicit missing-results marker) before exiting or validating, rejects missing/failed/skipped cases, and preserves the original Gradle failure code. Its EXIT trap is installed before ABI inspection and retains the original status even if logcat collection fails. Successful fixture output is also checked byte-for-byte. These fixtures copy the actual helper unchanged and replace only adb, git and Gradle command boundaries; they never invoke Android or real Gradle.

These are configuration and shell-boundary checks, not executed Android builds or emulator tests.

## Candidate routes

- Normal main-branch push and PR runs: both existing unit-test variants, both lint variants, debug compilation, development APK and instrumentation APK. No AAB or release APK packaging.
- Same-repository PR branch `fix/recorder-session-preservation`: after development checks pass, run the bounded recorder emulator checkpoint. This named-branch route avoids requiring a workflow-dispatch connector. An explicit `emulator_checkpoint` manual input is also available.
- Final-release artifacts: only an explicit workflow_dispatch with `release_checkpoint=true`, default false, after the same exact-head development checks pass. This produces unsigned artifacts without release signing or publication.

All jobs checkout and verify the PR head SHA or dispatch/push event SHA, and preserve tested/event identity. Actions are pinned to immutable commits verified from their official repositories. Standard public `ubuntu-24.04` runners are used; no larger runner or cache is introduced. Artifacts have seven-day retention. Shell pipelines use pipefail and key setup/build/test logs are retained on failure.

Existing hosted SDK installations and licenses are reused. Only exact missing components are installed, with stdin from `/dev/null`. No copied license hash, blanket license acceptance, emulator-runner license sweep or device-permission change is performed. The official runner inventory currently lists SDK36 and build-tools35.0.0; runtime checks still verify availability. An unavailable package, license or KVM permission fails explicitly and needs review.

## Instrumented coverage added, not yet executed

`SubtitleFileRecorderDeviceTest` contains bounded real Android-storage tests for same-second restart retention, failed replacement and internal-storage recovery, and seeded start/append/stop/discard sequences that verify every retained UTF-8 file. It depends on the recorder repair's internal clock seam. It never starts audio, projection or inference. Verified sample transcript files are copied to the debug app's test-evidence cache for CI retrieval.

`WhisperNativeLoadPreflightTest` separately requires advertised ARM64 support and loads the existing Whisper library. This is compatibility preflight only; it does not prove model loading, inference, native free-lifetime safety or session-race correctness. The app's ABI, Whisper Tiny, ML Kit and model choices are unchanged.

`scripts/run-recorder-emulator-check.sh` refuses physical devices, multiple devices and non-emulator runtimes. It requires a fresh evidence directory, runs native-load preflight before recorder flows, requires actual non-skipped test counts for the selected classes, and captures XML/logcat/transcript bytes. The emulator job is bounded to 35 minutes, with explicit boot deadlines and owned emulator cleanup. Its SDK image is Android35 Google APIs x86_64; actual ARM compatibility must pass, never be assumed. Preflight failure is an environment/ABI blocker, not a successful product test or a reason to replace Whisper.

The added instrumentation and complete Android suite have not compiled or run locally because execution-session verification became inaccessible. There is no emulator-pass or release-ready claim. UI rotation/permission handoffs, native inference/free lifetime, old-pump/new-session races and physical-device behavior remain outside this recorder batch.

## Review and publication prerequisites

Apply the separate recorder and CI/instrumentation patches together to the verified baseline (tree `617813a27d16b531bb04a142d2d2e8af77beabe7`). Review all changed files, recheck remote main, publish only the approved candidate branch and create its PR. Leave final-release input false. Verify the actual tested SHA, job outcomes, nonempty reports, retained transcript bytes and development APK checksum on the final candidate after any fixes. No workflow dispatch, GitHub upload/publication or device action was performed while preparing these files.

Sources:
- Runner inventory: https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md
- PR head versus merge commit: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- ARM translation background: https://android-developers.googleblog.com/2020/03/run-arm-apps-on-android-emulator.html
- Emulator action's blanket-license behavior (not adopted): https://github.com/ReactiveCircus/android-emulator-runner/blob/main/src/sdk-installer.ts

## First hosted-run setup regression

PR #40 head `8589687565de09ea3290ecfd5ae7d00c1e911234`, run
`38252602276`, stopped before Gradle: Ubuntu 24.04 already had the required
platform/build tools but `sdkmanager` was absent from PATH (exit 127). No app
assertion or emulator test ran. SDK setup now resolves the existing
`cmdline-tools/latest/bin` under the selected SDK root and exports it plus
`platform-tools` for both the current step and later GitHub steps. It accepts no
licenses and downloads no replacement command-line tools. A controlled execution
of all three actual workflow setup blocks, with packages present and missing,
failed six subcases before this change and passes afterward. The fake SDK tool
also checks missing-package installation receives empty stdin. Hosted Android
verification remains pending on the updated exact commit.
