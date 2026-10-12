# Actual model-cache/download integrity regression harness

No Android, Gradle build, native inference, real network, model payload, or historical Git checkout is used. Kotlin compiles the actual cache/tier/downloader/lock sources with a minimal Context boundary. HTTPS is an isolated JVM-local scripted transport. Frozen negative tests generate wrong bytes at production lengths; separate positive tests inject small expected fixture identities and hash actual file bytes.

Prerequisites: a JDK 17 and unpacked Gradle 8.13 (only its Kotlin compiler/JUnit/coroutines jars are used). Set JAVA_HOME and GRADLE_HOME as needed. Each JVM is limited to 384 MiB heap or less and 128 MiB metaspace. Do not run concurrently with an exclusive build slot unless authorized.

Run from the repository:

- `scripts/regression-evidence/download-integrity/run.sh before` compiles the unchanged current-working-tree pre-repair source archive. Expected: 18 tests, 16 integrity failures; cancellation and exclusive-writer controls pass. It exits 1 intentionally.
- `scripts/regression-evidence/download-integrity/run.sh after` compiles current production sources. Expected: frozen 18 tests pass, then the separately labeled fixture/ownership suite passes in another JVM.

Output defaults to ignored `evidence/` beside the harness. Override `N5_EVIDENCE_DIR` when recording evidence outside the repository and `CAPTION_ROOT` when running the harness outside its installed repository path. The `candidate` mode uses `$N5_EVIDENCE_DIR/candidate/*.kt` for isolated integration staging.

`baseline-source.tar.gz` contains actual unchanged working-tree files archived before the first red run on 2026-10-12. Git HEAD was historical and was not used to choose the baseline. The script checks the frozen negative test SHA-256 before either run. Inputs and assertions are identical before and after; fixture tests require the new internal test boundary and are not claimed as baseline-red evidence.

Promotion copy fallback is source-confirmed in the old baseline. Repaired atomic-move failure is tested at the actual filesystem operation boundary; this is not claimed as a reproduced legacy interrupted-copy window. Small fixture hashes prove the acceptance mechanism, not production-model/device/native correctness.
