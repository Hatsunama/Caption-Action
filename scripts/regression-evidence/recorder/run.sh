#!/bin/bash
set -euo pipefail
D=$(cd "$(dirname "$0")" && pwd)
: "${GRADLE_HOME:?Set GRADLE_HOME to an unpacked Gradle distribution}"
G="$GRADLE_HOME/lib"
CP=$(printf "%s:" "$G"/kotlin-stdlib-[0-9]*.jar "$G"/junit-4*.jar "$G"/hamcrest-core-*.jar "$G"/annotations-*.jar)
python3 "$D/prepare.py"
for VERSION in before after; do
    mkdir -p "$D/generated/$VERSION-classes"
    java -Xmx512m -XX:MaxMetaspaceSize=192m -cp "$G/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$D/generated/$VERSION-classes" \
      "$D"/*.kt "$D/generated/$VERSION.kt" "$D/generated/SubtitleFileRecorderSessionTest.kt" \
      > "$D/$VERSION-compile.log" 2>&1
    set +e
    java -Xmx192m -XX:MaxMetaspaceSize=128m "-Dcaption.recorder.outputEvidence=$D/generated/$VERSION-output" -cp "$D/generated/$VERSION-classes:$CP" \
      org.junit.runner.JUnitCore com.hatsunama.captionaction.data.SubtitleFileRecorderSessionTest \
      > "$D/$VERSION.log" 2>&1
    STATUS=$?
    set -e
    echo "$VERSION exit=$STATUS"
    cat "$D/$VERSION.log"
    if [[ "$VERSION" == before ]]; then BEFORE=$STATUS; else AFTER=$STATUS; fi
 done
[[ $BEFORE == 1 && $AFTER == 0 ]]
# A generic baseline crash is not a regression proof: require the observed data-loss assertion.
grep -q 'Starting again must never truncate the first transcript' "$D/before.log"
