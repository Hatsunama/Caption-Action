#!/bin/bash
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
R=$(cd "$D/../../.." && pwd)
: "${GRADLE_HOME:?Set GRADLE_HOME to an unpacked Gradle 8.11.1 distribution}"
G="$GRADLE_HOME/lib"
CP="$G/kotlin-stdlib-2.0.20.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar:$G/junit-4.13.2.jar:$G/hamcrest-core-1.3.jar:$G/annotations-24.0.1.jar"
python3 "$D/extract.py"
java -cp "$G/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$D/classes" "$D"/*.kt "$R/app/src/main/java/com/hatsunama/captionaction/audio/LiveWindowDrainer.kt" "$R/app/src/main/java/com/hatsunama/captionaction/service/CaptionDuplicateGate.kt" "$R/app/src/main/java/com/hatsunama/captionaction/inference/AsrJunkFilter.kt" "$D/../AndroidLogStub.kt" > "$D/compile.log" 2>&1
set +e
java -Dregression.version=before -cp "$D/classes:$CP" org.junit.runner.JUnitCore regression.ProductionBodyRegressionTest > "$D/before.log" 2>&1
BEFORE=$?
java -Dregression.version=after -cp "$D/classes:$CP" org.junit.runner.JUnitCore regression.ProductionBodyRegressionTest > "$D/after.log" 2>&1
AFTER=$?
set -e
printf 'before_exit=%s\nafter_exit=%s\n' "$BEFORE" "$AFTER" | tee "$D/status.txt"
cat "$D/before.log" "$D/after.log"
[[ $BEFORE == 1 && $AFTER == 0 ]]
