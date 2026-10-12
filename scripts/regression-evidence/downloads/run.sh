#!/bin/bash
set -uo pipefail
D=$(cd "$(dirname "$0")" && pwd)
R=$(cd "$D/../../.." && pwd)
: "${GRADLE_HOME:?Set GRADLE_HOME to an unpacked Gradle 8.11.1 distribution}"
G="$GRADLE_HOME/lib"
CP="$G/kotlin-stdlib-2.0.20.jar:$G/kotlinx-coroutines-core-jvm-1.6.4.jar:$G/junit-4.13.2.jar:$G/hamcrest-core-1.3.jar:$G/annotations-24.0.1.jar"
python3 "$D/prepare.py" || exit 1
AFTER=1
BEFORE=0
for MODE in before after; do
 java -cp "$G/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$D/$MODE/classes" "$D/$MODE/"*.kt "$D/ContextStub.kt" "$D/ActualDownloadManagerTest.kt" "$R/app/src/main/java/com/hatsunama/captionaction/ui/home/ModelGateSession.kt" "$R/app/src/main/java/com/hatsunama/captionaction/service/ModelDownloadControl.kt" > "$D/$MODE/compile.log" 2>&1
 if [[ $? != 0 ]]; then cat "$D/$MODE/compile.log"; exit 1; fi
 java -cp "$D/$MODE/classes:$CP" org.junit.runner.JUnitCore com.hatsunama.captionaction.ui.home.ActualReadyCallbackTest com.hatsunama.captionaction.ui.home.HomeActivity com.hatsunama.captionaction.service.ActualDownloadManagerTest > "$D/$MODE/results.log" 2>&1
 STATUS=$?
 if [[ $MODE == before ]]; then BEFORE=$STATUS; else AFTER=$STATUS; fi
 echo "$MODE exit=$STATUS"; cat "$D/$MODE/results.log"
done

[[ $BEFORE == 1 && $AFTER == 0 ]]
