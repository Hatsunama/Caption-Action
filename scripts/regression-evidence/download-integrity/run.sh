#!/bin/bash
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
R=${CAPTION_ROOT:-$(cd "$D/../../.." && pwd)}
E=${N5_EVIDENCE_DIR:-"$D/evidence"}
MODE=${1:-after}
G=${GRADLE_HOME:-/workspace/shared/android-test-tools/gradle-8.13}/lib
JAVA=${JAVA_HOME:-/workspace/shared/android-test-tools/jdk-17.0.20.1+1}/bin/java
CP="$G/kotlin-stdlib-2.0.21.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar:$G/junit-4.13.2.jar:$G/hamcrest-core-1.3.jar:$G/annotations-24.0.1.jar"
mkdir -p "$E/$MODE/classes"
if [[ $MODE == before && ! -d "$E/baseline-source" ]]; then tar -xzf "$D/baseline-source.tar.gz" -C "$E"; fi
actual=$(sha256sum "$D/IntegrityNegativeTest.kt" | cut -d' ' -f1)
[[ $actual == 645533ef86a113e7f25ed5bcaa5dd232bebe6313c893180f735fb2cbdf6db1c8 ]] || { echo "Frozen negative suite changed"; exit 2; }
if [[ $MODE == before ]]; then SRC=("$E/baseline-source/"*.kt); elif [[ $MODE == candidate ]]; then SRC=("$E/candidate/"*.kt); else SRC=("$R/app/src/main/java/com/hatsunama/captionaction/data/ModelTier.kt" "$R/app/src/main/java/com/hatsunama/captionaction/data/ModelCache.kt" "$R/app/src/main/java/com/hatsunama/captionaction/service/ModelDownloadManager.kt" "$R/app/src/main/java/com/hatsunama/captionaction/service/ModelDownloadControl.kt"); fi
TESTS=("$D/IntegrityNegativeTest.kt")
CLASSES=(com.hatsunama.captionaction.service.IntegrityNegativeTest)
if [[ $MODE != before && -f "$D/IntegrityFixtureTest.kt" ]]; then TESTS+=("$D/IntegrityFixtureTest.kt"); fi
"$JAVA" -Xmx384m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2 -cp "$G/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$E/$MODE/classes" "${SRC[@]}" "$D/ContextStub.kt" "${TESTS[@]}" > "$E/$MODE/compile.log" 2>&1 || { cat "$E/$MODE/compile.log"; exit 2; }
set +e
"$JAVA" -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2 -cp "$E/$MODE/classes:$CP" org.junit.runner.JUnitCore "${CLASSES[@]}" > "$E/$MODE/results.log" 2>&1
STATUS=$?
cat "$E/$MODE/results.log"
if [[ $STATUS != 0 ]]; then exit "$STATUS"; fi
if [[ $MODE != before && -f "$D/IntegrityFixtureTest.kt" ]]; then
 "$JAVA" -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2 -cp "$E/$MODE/classes:$CP" org.junit.runner.JUnitCore com.hatsunama.captionaction.service.IntegrityFixtureTest > "$E/$MODE/fixture-results.log" 2>&1
 STATUS=$?; cat "$E/$MODE/fixture-results.log"; exit "$STATUS"
fi
