# Caption muted-capture runtime: blocked review staging

This is local preparation only. Nothing here has been published or dispatched.
No app has been built, signed, installed, or executed by this staging task. No
cloud signing key is present. Public signed diagnostic delivery was separately
approved and published at4062451c315265c827161f7e3ac925ff8df39121. This staging
work neither fetched nor used those bytes; its signed identity remains unset.
The parent owns artifact/receipt adaptation and final review before any run.

## Hard stops

- `artifact_gate.SIGNED_IDENTITY = None` intentionally prevents any read-only
  verifier, download, prerequisite installation, SDK operation, guest start or app
  installation in the workflow. A local signing receipt/hash alone is insufficient.
- `runtime_contract.RECORD_AUDIO_UI`, `PROJECTION_UI_STEPS`,
  `ROOT_RUNTIME_REVIEW` and `DISCOVERY_REVIEW` remain `None`. All UI fixture names are synthetic examples,
  not observations of the pinned image. Unknown dialog/picker ownership, resource,
  text, activity, screen bounds, additional control or broader screen scope stops.
- Root must review the exact final diff and signed receipt/identity before any
  discovery gate is enabled. Capture also requires reviewed actual API36 limited
  consent selectors. A discovery gate cannot enable capture.
  There are no command-line/environment overrides for these review gates.

## Exact target and inherited boot constraints

Target is public `Hatsunama/Caption-Action`, branch
`probe/caption-muted-runtime-20261013`, with exactly one parent:
`4062451c315265c827161f7e3ac925ff8df39121` (immutable signed delivery).
Original unsigned delivery provenance remains
`893e8e06fc5f59914da194e65f72c105631a17cc`. The signed APK and signing receipt
are consumed from their existing delivery paths under tools/muted-capture-probe;
this candidate adds no duplicate signed artifact.
The existing delivery files, production app sources and workflows are untouched.
The prospective runtime diff adds only the runtime workflow and this directory;
no signed artifact/receipt is included in this candidate.

Boot adapter provenance is reviewed public Sign commit
`6cd8e58386d200507279ed12676ad6856d047bef`, helper blob
`4498aa9375d6c998076bc0f65946f7ae10ccdc52`, tests blob
`52107bd567ff66f9972d8634133d2b4b7474460c`. Changes are explicit Caption
repository/branch/parent/temp/AVD retargeting, removal of all unused cells/API26
fallback, exposure of the three owned emulator/server/ADB handles, and disabled
standalone boot entry point. No Sign repository guard is inherited accidentally.

One cell only: API36 Google APIs x86_64 revision7, expected4096-byte pages,
emulator37.2.12/platform-tools37.0.1. Public standard ubuntu-24.04, read-only
contents, pinned checkout, no persisted checkout credentials, two cores/2048MiB,
10-minute boot bound,20-minute runtime bound,25-minute job bound. Preaccepted SDK
license markers are copied and checked unchanged. Official libpulse0 prerequisite
is checked before installation. No paid/private/larger runner, cache, upload,
physical device, app build, test APK, signing command or cloud signing key.

UID-only KVM ACL lease and exact restoration remain. ADB owns a separate process
group, uses only loopback5038/emulator-5556, and disables USB/mDNS. Disposable
home/keys/SDK/guest are removed. Emulator cameras, snapshots and host audio stay
disabled; `-no-audio` is mandatory.

## Minimal two-stage discovery variant

The single workflow now selects `--mode discover-record` for both preflight and
execution. It does not also schedule the capture mode. This requires a separate
compiled `DISCOVERY_REVIEW` dictionary identifying that mode, exact parent and
`consent-ui-only-no-capture` scope; it remains None in the candidate. The signed
artifact gate is unchanged and always runs first. No environment/CLI value may
supply a review or signed identity.

1. `discover-record` boots/prepares the same muted no-audio guest, installs the
   exact verified dedicated package, arms the independent35-second watchdog,
   taps only the source-reviewed app Begin button, inspects the official
   PermissionController dialog, and stops. It never grants RECORD_AUDIO.
2. After root reviews the exact observed ordinary RECORD_AUDIO selector, a
   separately approved `discover-projection` run can tap only that reviewed
   foreground-only permission control, verify its grant, check idle state, and
   tap Begin again. It inspects the official MediaProjection dialog then stops.
   It never taps any projection grant, scope dropdown, confirmation or picker,
   including familiar-looking single-app controls. Broad/unfamiliar scope is
   evidence for review only, never permission to continue.

Both modes reuse the original bounded boot, no-audio, volume0 and artifact
checks. Discovery emits only bounded, allowlisted synthetic dialog facts:
verified official owner/activity, semantic keys for fixed approved text strings,
static resource IDs/classes in constrained official namespaces, verified bounds,
enabled/clickable booleans, counts of redacted unknown labels/controls, and a
boolean when the known entire-screen option is visible. It emits no raw unknown
text, content descriptions, XML, dumpsys output, screenshot or unrelated logs.
Owner/foreground/probe-label ambiguity blocks even this filtered publication.
Unknown wording may therefore remain unreviewable without a further explicitly
reviewed allowlist update; no generalized text fallback exists.

Discovery never starts the collector or calls the capture implementation. After
inspection, service absence plus exact-package force-stop, package/process and
idle readbacks, watchdog disarm, uninstall and full host cleanup are required.
Successful inspection ends as `DISCOVERY_STOPPED` with runtime_pass=false,
capture_started=false and projection_granted=false. It is not capture proof.

## Runtime and consent plan

The only volume write is guest STREAM_MUSIC to0, after inspecting installed
`media help`, with independent readback. All other audio/projection/media-session
checks are bounded read-only dumpsys. Current sections must be known and unambiguous;
no historical line can replace current-state proof. Unknown/retained audio objects
are rejected conservatively. Missing built-in speaker, source progress, audio
support or no-audio limitations remain environment-unavailable/inconclusive.
Never enable host audio, change backend, raise volume or relax thresholds.

Only the verified fresh dedicated package and its ProbeActivity are used, without
`-g`. Actual unique visible enabled node bounds determine each one-time tap;
foreground activity and window ownership are verified twice. The first Begin
requests ordinary RECORD_AUDIO consent and permission readback. The app remains
idle afterward. Before the second Begin, a separately spawned watchdog confirms
it is armed with a monotonic35-second deadline. Projection steps must be the
reviewed official single-app flow selecting this probe. Entire-screen or other
broader scope has no implemented approval path. There is no permissive watcher,
appops override, pm grant, reused token or service injection.

The independent watchdog detects deadline or parent EOF, bounds its own exact
package/serial force-stop and stopped-state readback, and destroys only the saved
owned guest process group if ADB/guest stopping fails. It does not depend on the
main UI/log parser. PID/UID/starttime/process-group/session checks reject identity
changes; descendant cleanup remains required even after leader exit.

## Evidence and teardown gate

Only the fresh verified PID and CaptionCaptureProbe tag are collected. Scalar
logs are bounded to16KiB. Exactly SILENT, PERMITTED_TONE, DENIED_TONE are required
in order, each at least32000 samples/64000 source frames. Permitted RMS must be
at least100 and toneFraction at least0.70; silent/denied RMS must be below2.
Counts, finite bounded values, phase hashes and exact final fields are checked.
Duplicates, unknown/failing lines, nonfinite numbers, missing phases, ABORT,
teardown_unverified and late contradictory success/failure all reject the run.

App final verified teardown is followed by independent stopped service,
projection/audio/session/zero-volume checks, mandatory exact-package force-stop,
process/package-stopped readback and repeated idle checks. The complete log is
parsed again after stopping the collector. Only then may the watchdog disarm.
Exact-package uninstall, owned process-group destruction, guest/home/SDK removal
and KVM ACL restoration must succeed before runtime_pass becomes true. Public
output is aggregate JSON only; no raw UI, dumpsys, audio, screenshots or files
are uploaded. A pass would apply only to own-UID platform capture on this exact
synthetic muted emulator, never phone/production Live/ASR/cross-app acceptance.

## Offline verification

Run only the synthetic suite while gates remain unset:

    python3 -m unittest discover -s tools/caption-muted-runtime -p 'test_*.py' -v

Tests use in-memory/mock SDK/ADB/emulator commands, synthetic ZIP signatures and
UI/dumpsys fixtures, plus tiny local Python children for process-bound/watchdog
checks. No real SDK, signer, ADB, emulator or app is executed. Some artifact tests
read the already-frozen unsigned APK/source in a local workspace if present.

API36 parser source references (not proof of the actual Google image's output):
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/AudioService.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/RecordingActivityMonitor.java
- https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/audiopolicy/managerdefault/AudioPolicyManager.cpp
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/media/VolumeCtrl.java
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/media/grow/media-projection
