# Compression audit and reduction plan only

Baseline: main `1e06a516c4a8f42c6ec51c16da660837ec6584c6` (0.3.28 / 51).
Appearance and behavior are fixed constraints. No compression candidate is applied
in this draft. Bug repairs and required unsigned/API preparation are separate
behavior-affecting work, not size optimizations.

## Measured baseline versus missing measurements

Static repository measurements, from ZIP entry inspection:

| Component | Repository archive bytes | arm64 native uncompressed bytes | arm64 native compressed bytes inside AAR |
|---|---:|---:|---:|
| sherpa-onnx-1.13.8.aar | 50,129,134 | 31,927,176 | 11,956,499 |
| whisper-android-1.0.0.aar | 1,100,643 | 2,817,424 | 1,079,019 |
| SenseVoice tokens.txt | 315,894 | n/a | n/a |

The Sherpa AAR contains four ABIs, but `app/build.gradle.kts:18-20` already filters
shipping to arm64-v8a. Removing other AAR ABIs would mostly reduce repository/input
size, not the current delivered native payload. AAR compression ratios are not
APK or Play-download savings. APK, AAB, installed size, dex/resources, store split
size, and measured before/after savings remain **unmeasured** because full Android
packaging has not completed. No fabricated estimated MB or percentage is used.

Reproduce static inspection: `python3 scripts/audit-package.py app/libs/*.aar`.
Keep final baseline artifacts, hashes, toolchain and dependency report before any
approved reduction. Compare identical app version, ABI, SDK, signing and package
configuration; only the candidate under test may differ.

## Prioritized small batches

### 1. Release shrinking, suspected opportunity; not applied

- Location: `app/build.gradle.kts:23-31`, `release.isMinifyEnabled`; current false.
  `app/proguard-rules.pro:1-2`; current broad inference keep.
- Responsibilities: Kotlin/reflection, Android resource references, ML Kit APIs,
  generated ViewBinding, JNI entrypoints, classes/fields constructed from C++.
- Dynamic-use trace: Whisper AAR has consumer rules for `dev.ffmpegkit.whisper.*`
  and native signatures. Sherpa AAR consumer rules are empty; its JNI accesses
  `com.k2fsa.sherpa.onnx` model/config/result types. Library reflection is not
  exhaustively traced, so deletion safety is not proven.
- Proposed batch: enable R8 first with explicit JNI keep rules; inspect usage and
  mapping outputs. Resource shrinking is a separate follow-on batch after that.
- Risks: JNI lookup failures, reflection removal, missing resources/styles/languages,
  changed cold-start behavior. Broad `-dontwarn` is not a substitute for fixing
  missing dependencies or proving reachability.
- Verification: full tests/lint and release build; compare dex/resource/native ZIP
  groups; exact optimized artifact cold-starts on both phones; Tiny and retained
  engine load, live/mic, all language/dual paths, download and cancellation tests.
- Savings: unknown until an approved isolated A/B build is measured.

### 2. Retired SenseVoice shipping lane, proven payload; behavior-affecting proposal

- Files/symbols: `app/build.gradle.kts:66` Sherpa dependency;
  `data/ModelTier.kt:10-22` FAST metadata; `InferenceEngineFactory.kt:37-45,62-69`
  Sherpa construction/native probe; `SherpaInferenceEngine.kt` engine lifecycle,
  token copying, ASR and language correction; `assets/sherpa/sensevoice-tokens.txt`.
- Callers: Home's `showModelGateThenStart` uses only BALANCED; Mic's engine startup
  hardcodes BALANCED. However `CaptionOverlayService.kt:342-353` reads stored tier,
  resolves `ModelTier.fromId`, checks cache and calls the factory. Thus Sherpa is
  not globally unreachable. Its retained compatibility responsibility exists
  even though the visible picker was removed.
- Proposed prerequisite decision: approve legacy `fast`→Tiny migration explicitly.
  This changes behavior/model output, can require a new Tiny download and removes
  old SenseVoice inference compatibility. It does not satisfy an unconditional
  "all current behavior unchanged" requirement and is not an automatic cleanup.
- Preserve every other preference and caption/history file. Never delete old model
  downloads silently. Handle missing Tiny through the normal download gate.
- Verification before removal: enumerate factory/cached tier consumers and external
  starts; migration + idempotence tests; offline old-fast user with/without Tiny;
  download fail/cancel/retry; current Live/Mic source/target and English fast path;
  exact-artifact cold starts on both phones. Retained engine responsibilities must
  either remain or be knowingly retired.
- Measured contribution above is a removal ceiling for native ZIP entries, not a
  measured final APK saving. A separately preserved implementation sketch and three
  passing migration helper tests are evidence of a proposal, not approval or proof
  of integrated migration correctness.

### 3. Direct dependency/resource cleanup, suspected only

- Locations: dependencies at `app/build.gradle.kts:68-82`; activity layouts,
  `res/drawable*`, `res/mipmap*`, strings/styles, generated binding.
- Material/AppCompat/Activity/Lifecycle/DataStore/coroutines/ML Kit all have visible
  callers. A direct dependency may be transitive elsewhere; removing its declaration
  need not reduce the resolved graph. Resource lookup includes XML/style/theme and
  Android-generated paths, not just text-search hits.
- Next read-only evidence: resolved release dependency tree and R8/resource usage
  reports; trace candidate symbol/asset callers and dynamic references individually.
  No specific image, locale, drawable or dependency is yet proven removable.
- Risks: visual/font/layout regression, lost language support, missing runtime API.
- Verification: one candidate per batch, full checks, screenshot comparison and
  impacted flow on both phones; quantify actual artifact deltas.
- Savings: unmeasured. Do not resample artwork, prune languages, change models,
  remove features or alter native runtime as a compression shortcut.

## Acceptance gate

A plan is not permission to execute reductions. Each proposed batch requires its
own scoped implementation decision. Preserve an exact baseline and prove its
regressions before changing code. Passing helper tests alone establishes neither
runtime equivalence nor release readiness. Any final code/build change requires
fresh tests and cold-start/feature checks on both phones.
