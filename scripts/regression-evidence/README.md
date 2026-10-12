# Production fail-before/pass-after evidence

Normal Gradle unit tests are the primary maintained suite, including real Home gate and Service/Mic lifecycle tests. No scripts below create an APK or signing key.

## Model download/cache integrity (current)

With JDK 17 and an unpacked Gradle 8.13 distribution (used only for its compiler/JUnit jars):

```sh
export GRADLE_HOME=/path/to/gradle-8.13
bash scripts/regression-evidence/downloads/run.sh
```

The supported compatibility entry point now runs `download-integrity/run.sh` against an archived actual working-tree baseline and current production code. It requires no historical Git commit. Expected baseline: 18 tests, 16 integrity failures; current: 18 passing frozen tests plus the separately labeled internal-fixture/ownership suite. See `download-integrity/README.md` for direct commands, strict negative-suite hash checks, and limitations.

The original historical-Git and extracted-Home-callback implementation is retained in `downloads/historical-harness.tar.gz`, alongside its historical sources, for provenance only. It is not a current verification entry point. Real Home cancellation/readiness and Service/Mic native-owner assertions run in the maintained Gradle suite, rather than copying callback bodies into a toy UI harness. Actual downloader cancel-before-start, one-way queued cancellation, and exclusive-writer assertions are retained and strengthened in the replacement.

## Historical audio counterfactual

The audio script still requires baseline commit `1e06a516` and an unpacked Gradle 8.11.1 distribution:

```sh
export GRADLE_HOME=/path/to/gradle-8.11.1
bash scripts/regression-evidence/audio/run.sh
```

Expected audio baseline: 5 tests / 3 failures; current: 5 / 0. It retains actual queue-draining methods and the exact-duplicate predicate; queue/hardware boundaries are fixtures. This is bounded JVM evidence, not real-network, native inference, or physical-device validation.
