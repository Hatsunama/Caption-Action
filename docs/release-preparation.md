# Release preparation (draft, 2026-10-12)

This branch is **not a store-ready release**. It preserves application ID
`com.hatsunama.captionaction`, version 0.3.28 / code 51, and the existing Whisper
Tiny / ML Kit paths. A store upload needs a version code greater than that store's
last uploaded version; verify both stores before selecting one. No signing keys,
passwords, certificates or signing lineage have been read, created or changed.

## Build and verification

Use JDK 17+ and Android SDK platform 36 / build-tools 35.0.0. The wrapper pins
Gradle 8.11.1, with Android Gradle Plugin 8.10.1. Install SDK packages only after
accepting Google's SDK terms. The repository does not auto-accept licenses.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease --no-daemon --max-workers=1
./gradlew :app:preparePlayAab :app:prepareSolanaApk --no-daemon --max-workers=1
python3 scripts/audit-package.py app/build/distribution/play/*.aab app/build/distribution/solana/*.apk
```

Outputs:
- Play: `app/build/distribution/play/caption-action-play-unsigned.aab`
- Solana: `app/build/distribution/solana/caption-action-solana-unsigned.apk`

Both are unsigned. `release` no longer falls back to the debug signing key.
Do not submit or install these as a signed production release. The debug variant
keeps its `.debug` suffix. Artifacts remain separate so a Play upload bundle is
not confused with the directly distributed APK.

## Signing handoff, only after validation

Keep existing signing keys **only on C:** on the owner's Windows computer. Keep
checkout, SDK, Gradle cache, temporary files, builds and output on a non-C: drive.
Never copy keys into this repository, CI, or a cloud workspace. Do not make a new
key to work around a mismatch with the existing production certificate.

The owner signs locally with the existing authorized keys after verifying the
certificate identity and lineage used by each store. Google Play's upload key
may differ from its app-signing key; do not assume that the upload certificate is
the correct identity for the Solana APK. Use the established Solana certificate
and any existing signing lineage. Before any distribution, verify the signed
APK certificate and update compatibility; validate the AAB with bundletool and
verify its upload signature. Do not put passwords in command history, Gradle
properties, repository files, or this document. This branch intentionally has no
production signing configuration that reads credentials.

## Size audit only

No dependency, asset, model-tier or shrinking change is applied for size reduction.
Release minification remains disabled, Sherpa and tokens remain included, and all
saved tier behavior is preserved. See [compression audit](compression-audit.md)
for measured static contributions, risks and proposed batches. A proposed
compression patch is preserved separately and is not part of this draft.

`audit-package.py` reports ZIP entry sizes and native ELF LOAD/RELRO checks using
only Python's standard library. It does not prove on-device compatibility or
APK ZIP alignment. A completed preparation build measured AAB 26,637,091 bytes and APK 59,303,463 bytes
before the additional reset-race repair; these are not compression savings. Final
checks and artifact hashes must be refreshed after that repair.

## Platform gates

Official requirements verified 2026-10-12:
- [Google Play target API](https://developer.android.com/google/play/requirements/target-sdk):
  new apps/updates must target API 36 from August 31, 2026. This branch targets 36.
- [AGP compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes):
  AGP 8.10 supports API 36 and requires Gradle 8.11.1.
- [16 KB native compatibility](https://developer.android.com/guide/practices/page-sizes):
  check native ELF alignment, APK/AAB ZIP alignment, and runtime behavior. Merely
  updating Gradle cannot rebuild the bundled precompiled native libraries.

At the audited base commit every bundled arm64 PT_LOAD segment is 16 KiB aligned,
but GNU_RELRO end alignment fails the current official check for
`libc++_shared.so`, `libonnxruntime.so`, `libsherpa-onnx-c-api.so`,
`libsherpa-onnx-cxx-api.so`, and `libsherpa-onnx-jni.so`. `libwhisper.so` passes both
static ELF checks. No native libraries were replaced or removed. The libc++ runtime from official
NDK 28c also fails the literal RELRO-end modulus criterion, although its writable
data is in a separate LOAD outside the rounded protection range, as is the
original runtime. Therefore this is a documentation-check discrepancy and a
required runtime-verification gap, not proof that Whisper crashes on 16 KB.
Changing the NDK version alone is not evidence of a fix.

The completed package additionally includes DataStore, ML Kit language-ID and
translation native libraries, giving nine arm64 libraries in total. All nine pass
PT_LOAD alignment; eight (all except Whisper) fail the literal RELRO-end modulus.
The prepared APK passes `zipalign -c -P 16 -v 4`; bundletool 1.18.3 validates the AAB
and its config requests `PAGE_ALIGNMENT_16K`. These static successes do not close
the native-runtime gate; final rebuilt artifacts must be checked again.

API 36 enforces edge-to-edge; activity roots now request system-window insets.
The app does not intercept legacy `onBackPressed` or launch from BOOT_COMPLETED.
Verify Home, permission screens, Mic and dialogs with gesture navigation,
three-button navigation, display cutouts, rotation and large font sizes.

## Regression and physical checks

JVM regressions cover newest-window Live catch-up, ordinary sample preservation,
finite duplicate suppression, restart behavior, dismissed/reopened download
gates, pre-start cancellation, active connection shutdown and serialized writes.
Mic FIFO source was compared against the base implementation and is unchanged.

Device-only checks still required after all build checks pass:
- Live: playback capture grant/deny/revoke; same phrase after a pause and restart;
  long playback with slow inference; English fast path and non-English/dual MT.
- Mic: FIFO completeness, short phrases, From/To pairs, rapid stop/start.
- Downloads: Back, outside tap, Cancel, reopen, network stall, interruption and
  resume; no unexpected capture start or overlapping partial-file writes.
- Final release: load Whisper and retained engines, then run offline ASR/MT
  to detect JNI/reflection regressions that JVM unit tests cannot detect.
- Android 16 and a real/emulated 16 KB environment: launch, capture, model load,
  translation and background/foreground transitions.

See also [physical-test.md](physical-test.md). No store submission, publishing,
merge, signing, or paid model job is authorized by this preparation document.

## Validation status after the reset-generation repair

- PASS: declared Android Gradle Debug and Release unit suites, **90 tests each**,
  zero failures/errors/skips. The helper-only fallback is no longer the sole evidence.
- PASS: full strict Debug and Release lint, zero errors, 99/97 warnings retained.
  Four baseline API errors were resolved with a precise API29 private-method
  contract annotation; callers already guarded API29. No broad suppression added.
- PASS: actual production fail-before/pass-after harnesses: audio/dedup 3 failures
  before and 0 after (5 tests); downloads/gates 4 before and 0 after (4 tests).
  Introduced reset/cancel/restart race: 5 failures before and 0 after (15 tests).
  Portable original-bug harnesses are under `scripts/regression-evidence`.
- PASS: final local unsigned AAB/APK packaging, source diff checks and workflow
  actionlint. GitHub clean-build CI passed before the reset repair and reruns on
  the final commit. Use its exact commit status, never an older green run.
- PASS: original Mic FIFO bodies unchanged; resolved release dependencies captured.
- NOT RUN: physical-device, real Android dialog/network, offline ASR/MT and 16 KiB
  runtime checks; signing/store validation. No signing keys generated or accessed.
- RELEASE BLOCKED: final signed artifacts must cold-start on both phones and pass
  affected-feature tests. Any source/build change requires refreshed verification.
  Documented pre-existing native inference/release lifetime risk also remains open.
  Do not recommend installation, publishing or release while these gates remain open.

Package hashes are emitted with each CI artifact in `SHA256SUMS`, avoiding a claim
that a later rebuilt or signed package is identical to an earlier local archive.
