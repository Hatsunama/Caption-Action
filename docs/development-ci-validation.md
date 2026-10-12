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

## Bounded software-emulation experiment and runtime identity

Hosted run `38253081537` at exact head
`84e5e662d4336b02c42300ecf706b4ccc8e85a46` passed 111 unit tests in each
of debug/release, including six recorder cases per variant, with no failures,
errors or skips. Both lint tasks and both development/instrumentation APK builds
passed. These are variant executions, not 222 unique tests. Its emulator job
stopped at KVM access checks before any boot, ABI check or device assertion.

The [official command-line documentation](https://developer.android.com/studio/run/emulator-commandline)
now explicitly calls `-accel off` unsupported and very slow, requiring
`ANDROID_I_WANT_MY_TCG=yes`. The next attempt uses that documented experimental
opt-in only when existing KVM read/write access is absent. It changes no device
permissions, records mode and emulator version, keeps the same API 35 Google APIs
x86_64 image and ARM64/native-load preflight, and bounds boot to ten minutes and
the combined test helper to twenty minutes (35-minute job ceiling). A timeout is
an environment/performance blocker, not a product pass or an automatic rerun.

The emulator job rebuilds APKs and may have a different disposable signing key
from the development job. After each test phase, the helper queries the installed
app and test-package paths, pulls their actual base APK bytes, verifies and logs
signing certificates with `apksigner`, and stores SHA256 plus the pulled APKs with
that phase's results. Missing packages, split packages or unverifiable signatures
prevent a passing result; failed Gradle status remains primary. The guarded disposable-emulator command passes
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, verified against
Caption's actual AGP 8.10.1 bytecode (not Finder's 8.13.2).
`DeviceProviderInstrumentTestTask.CreationAction` maps this option to
`keepInstalledApks`; `TestRunnerFactory` negates it for UTP
`uninstallApksAfterTest`. `UtpConfigFactory.createApkInstallerPlugin` sets that
boolean on both the target-app and test-APK install entries. Therefore both
remain available for capture; emulator teardown still disposes of the runtime.
AGP 8.13.2's installer was also checked and uses the same per-APK flag.
The fake Gradle boundary asserts the exact retention property on every invocation. No
claim is made that the separately uploaded development-job APK was device-tested.
Controlled fixtures verify both acceleration branches, exact installed-byte
capture, missing/split identity rejection and preservation of original failures.

The first software-emulation setup run (`38256163631`, head
`bb1f986ed787455ee19592ed6fb96e88414ef05a`) also passed the full unit/lint/build
job, verified from raw JUnit reports (111 tests per variant, zero skipped or
failed). It never started an emulator: the version command failed loading
`libpulse.so.0`. This is a host-library prerequisite failure, not evidence about
software-emulation performance. The workflow now logs the actual QEMU dependency
list before and after installing only the missing official Ubuntu
[`libpulse0` client library](https://packages.ubuntu.com/noble/libpulse0), without
recommended packages, audio daemon configuration, microphone access or security
permission changes. It records the installed package version and requires the
real emulator version command to succeed before boot. A controlled fixture of
the prior boot step reproduces exit 127, while the updated runtime step installs
only that library when absent and skips installation when present.

## Explicit AVD ownership and early-exit detection

Run `38257767682` (head `5134a37de3cd45898842590751d79978824f5fd6`)
passed all 111 tests per variant and both lint/build paths. The vendor emulator
37.2.12.0 version preflight passed after installing the missing client library.
Its launch then immediately reported an unknown `caption-ci` AVD and no root
INI under `$HOME/.android/avd`; the blind ADB wait obscured that early exit until
ten minutes elapsed. No guest boot, native-load check or recorder test ran.
The earlier avdmanager output confirmed x86_64 selection but did not record the
created destination, so the precise previous destination is unknown; a different
default home is a hypothesis, not an established fact.

Creation and lookup now share the documented
[`ANDROID_AVD_HOME`](https://developer.android.com/tools/variables), pointing to
a unique run-owned temporary directory. Creation also receives an explicit
content path. Both tools must list the same AVD, and the root INI plus content
config must exist before launch. Only named path environment values, creation
output, the root INI and lists are preserved. No global configuration is edited.
Boot polling checks only the owned emulator PID while waiting for ADB and the
boot property, preserving the existing ten-minute overall deadline. Early exit
is reported immediately with its status instead of waiting out a blind ADB call.
Controlled actual-workflow fixtures cover mismatched defaults, missing INI and
immediate emulator failure; all three failed before the change and pass after.
