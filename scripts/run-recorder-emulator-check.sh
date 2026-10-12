#!/bin/bash
# Run only against a separately provisioned, approved emulator. No SDK/license setup here.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
SERIAL=${ANDROID_SERIAL:-}
assert_emulator_only() {
  [[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Refusing a missing or physical-device serial.' >&2; exit 2; }
  mapfile -t devices < <(adb devices | awk 'NR > 1 && NF { print $1 }')
  [[ ${#devices[@]} == 1 && "${devices[0]}" == "$SERIAL" ]] || { echo 'Require exactly one connected device: the selected emulator.' >&2; exit 2; }
  [[ $(adb -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Selected runtime is not an emulator.' >&2; exit 2; }
}
assert_emulator_only
EVIDENCE=${RECORDER_EMULATOR_EVIDENCE:-ci-emulator-evidence}
[[ ! -e "$EVIDENCE" ]] || { echo "Use a fresh evidence directory: $EVIDENCE" >&2; exit 2; }
mkdir -p "$EVIDENCE"
finish() {
  local status=$?
  trap - EXIT
  set +e
  printf '%s\n' "$status" > "$EVIDENCE/exit-status.txt"
  adb -s "$SERIAL" logcat -d -v threadtime 2>&1 | tail -n 5000 > "$EVIDENCE/logcat.txt"
  exit "$status"
}
trap finish EXIT
printf '%s\n' "$(git rev-parse HEAD)" > "$EVIDENCE/TESTED_SHA"
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist > "$EVIDENCE/abis.txt"
grep -q 'arm64-v8a' "$EVIDENCE/abis.txt" || { echo 'This runtime lacks the existing app ARM64 ABI.' >&2; exit 3; }
GRADLE=${GRADLE_BIN:-./gradlew}
run_phase() {
  local phase=$1 class=$2 expected=$3
  assert_emulator_only
  local -a pipeline_status
  local copy_status=0
  # Do not let errexit discard the failed run's raw evidence. Preserve Gradle's own
  # status separately from tee/copy/validation failures, which must not replace it.
  set +e
  "$GRADLE" :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$class" \
    --no-daemon --max-workers=1 --no-parallel --stacktrace 2>&1 | tee "$EVIDENCE/$phase.log"
  pipeline_status=("${PIPESTATUS[@]}")
  set -e
  printf '%s\n' "${pipeline_status[0]}" > "$EVIDENCE/$phase-gradle-exit.txt"
  printf '%s\n' "${pipeline_status[1]}" > "$EVIDENCE/$phase-log-exit.txt"
  if [[ -d app/build/outputs/androidTest-results/connected ]]; then
    cp -a app/build/outputs/androidTest-results/connected "$EVIDENCE/$phase-results" || copy_status=$?
  else
    mkdir -p "$EVIDENCE/$phase-results"
    printf 'No connected-test XML directory was produced.\n' > "$EVIDENCE/$phase-results/missing-results.txt"
    copy_status=1
  fi
  printf '%s\n' "$copy_status" > "$EVIDENCE/$phase-evidence-exit.txt"
  if (( pipeline_status[0] != 0 )); then return "${pipeline_status[0]}"; fi
  if (( pipeline_status[1] != 0 )); then return "${pipeline_status[1]}"; fi
  if (( copy_status != 0 )); then return "$copy_status"; fi
  python3 - "$EVIDENCE/$phase-results" "$class" "$expected" <<'PY'
import sys, pathlib, xml.etree.ElementTree as ET
folder, classname, expected = pathlib.Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
cases = []
for path in folder.rglob('*.xml'):
    root = ET.parse(path).getroot()
    cases += [case for case in root.iter('testcase') if case.get('classname') == classname]
assert len(cases) == expected, f'{classname}: expected {expected} executed test cases, got {len(cases)}'
assert all(not list(case.iter('failure')) and not list(case.iter('error')) and not list(case.iter('skipped')) for case in cases), 'Failed or skipped instrumentation is not a pass'
print(f'{classname}: {len(cases)} actual cases passed')
PY
}
run_phase native-preflight com.hatsunama.captionaction.inference.WhisperNativeLoadPreflightTest 1
run_phase recorder-sequences com.hatsunama.captionaction.data.SubtitleFileRecorderDeviceTest 3
for name in first-session second-session; do
  adb -s "$SERIAL" exec-out run-as com.hatsunama.captionaction.debug cat "cache/recorder-ci-evidence/$name.txt" > "$EVIDENCE/$name.txt"
done
sha256sum "$EVIDENCE"/*-session.txt > "$EVIDENCE/transcript-SHA256SUMS"
