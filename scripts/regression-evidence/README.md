# Production fail-before/pass-after evidence

Run from a checkout containing baseline commit `1e06a516` (fetch full history if shallow), with JDK 17 and an unpacked Gradle 8.11.1 distribution:

```sh
export GRADLE_HOME=/path/to/gradle-8.11.1
bash scripts/regression-evidence/audio/run.sh
bash scripts/regression-evidence/downloads/run.sh
```

The scripts compile Kotlin with the compiler and JUnit libraries in that Gradle distribution; no Android APK or signing key is created. The same assertions must fail on original production bodies and pass on current bodies. Audio extraction retains the actual queue-draining methods and original exact-duplicate predicate; the queue/hardware boundary is a fixture. Download tests compile the entire original/current downloader/cache/model source against a filesystem context stub and fake HTTP; the two Home continuation bodies are mechanically extracted. Generated files are ignored.

Expected: audio baseline 5 tests / 3 failures, current 5 / 0; downloads baseline 4 / 4, current 4 / 0. This is bounded evidence, not Android lifecycle, real-network, native inference or physical-device validation. Normal Gradle unit tests remain the primary maintained suite.
