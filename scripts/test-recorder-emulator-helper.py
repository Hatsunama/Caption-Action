#!/usr/bin/env python3
"""Exercise the unchanged helper against controlled command boundaries; no Android or Gradle run."""
from contextlib import contextmanager
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
HELPER = Path(os.environ.get("CAPTION_EMULATOR_HELPER", ROOT / "scripts/run-recorder-emulator-check.sh"))

ADB = '''#!/usr/bin/env python3
import os,sys
args=sys.argv[1:]
if args == ['devices']:
 print('List of devices attached\\nemulator-5554\\tdevice')
elif args[-2:] == ['getprop','ro.kernel.qemu']:
 print('1')
elif args[-2:] == ['getprop','ro.product.cpu.abilist']:
 print(os.environ.get('FAKE_ABIS','x86_64,arm64-v8a'))
elif 'logcat' in args:
 print('controlled emulator logcat')
 sys.exit(int(os.environ.get('FAKE_LOGCAT_EXIT','0')))
elif 'pm' in args and 'path' in args:
 if os.environ.get('FAKE_APK_MODE')=='missing': sys.exit(0)
 print('package:/data/app/'+args[-1]+'/base.apk')
 if os.environ.get('FAKE_APK_MODE')=='split':print('package:/data/app/split.apk')
elif 'pull' in args:
 import pathlib
 pathlib.Path(args[-1]).write_bytes(('installed '+args[-2]).encode())
elif 'exec-out' in args:
 sys.stdout.buffer.write('verified fixture 🌏\\n'.encode('utf-8'))
else:
 raise SystemExit('Unexpected fake adb invocation: '+repr(args))
'''
GRADLE = '''#!/usr/bin/env python3
import os,pathlib,shutil,sys,xml.etree.ElementTree as ET
assert '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' in sys.argv, 'AGP must retain both runtime APKs for identity capture'
classname=next(arg.split('=',1)[1] for arg in sys.argv if arg.startswith('-Pandroid.testInstrumentationRunnerArguments.class='))
phase='NATIVE' if classname.endswith('WhisperNativeLoadPreflightTest') else 'RECORDER'
with open('invocations.txt','a') as f:f.write(phase+'\\n')
print('controlled '+phase+' Gradle output',flush=True)
folder=pathlib.Path('app/build/outputs/androidTest-results/connected')
if folder.exists():shutil.rmtree(folder)
mode=os.environ.get('FAKE_'+phase+'_MODE','passed')
if mode!='missing':
 folder.mkdir(parents=True)
 suite=ET.Element('testsuite')
 for index in range(1 if phase=='NATIVE' else 3):
  case=ET.SubElement(suite,'testcase',classname=classname,name='case'+str(index))
  if index==0 and mode in ('failure','error','skipped'):ET.SubElement(case,mode)
 ET.ElementTree(suite).write(folder/'TEST-fixture.xml')
sys.exit(int(os.environ.get('FAKE_'+phase+'_EXIT','0')))
'''

class EmulatorHelperEvidenceTest(unittest.TestCase):
    @contextmanager
    def fixture(self, **settings):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            shutil.copyfile(HELPER, root / 'scripts/run-recorder-emulator-check.sh')
            binaries = root / 'bin'
            binaries.mkdir()
            for name, source in [('adb', ADB), ('apksigner', '#!/bin/sh\nprintf \"Signer #1 certificate SHA-256 digest: fixture\\n\"\n'), ('gradle', GRADLE), ('git', '#!/bin/sh\nprintf "%s\\n" 0000000000000000000000000000000000000000\n')]:
                path = binaries / name
                path.write_text(source)
                path.chmod(0o755)
            evidence = root / 'evidence'
            env = dict(os.environ, APKSIGNER=str(binaries / 'apksigner'), PATH=str(binaries) + os.pathsep + os.environ['PATH'], ANDROID_SERIAL='emulator-5554', GRADLE_BIN=str(binaries / 'gradle'), RECORDER_EMULATOR_EVIDENCE=str(evidence), **settings)
            result = subprocess.run(['bash', str(root / 'scripts/run-recorder-emulator-check.sh')], env=env, capture_output=True, text=True, timeout=10)
            yield result, root, evidence

    def assert_final_evidence(self, evidence, status):
        self.assertEqual(str(status), (evidence / 'exit-status.txt').read_text().strip())
        self.assertIn('controlled emulator logcat', (evidence / 'logcat.txt').read_text())

    def test_failed_native_gradle_retains_raw_xml_log_and_original_exit(self):
        with self.fixture(FAKE_NATIVE_EXIT='7', FAKE_NATIVE_MODE='failure') as (result, root, evidence):
            self.assertEqual(7, result.returncode, result.stderr)
            self.assertTrue((evidence / 'native-preflight-results/TEST-fixture.xml').is_file())
            self.assertIn('controlled NATIVE Gradle output', (evidence / 'native-preflight.log').read_text())
            self.assertEqual('7', (evidence / 'native-preflight-gradle-exit.txt').read_text().strip())
            self.assertEqual('NATIVE\n', (root / 'invocations.txt').read_text())
            self.assert_final_evidence(evidence, 7)

    def test_failed_recorder_gradle_retains_both_phases_and_original_exit(self):
        with self.fixture(FAKE_RECORDER_EXIT='9', FAKE_RECORDER_MODE='error', FAKE_LOGCAT_EXIT='17') as (result, root, evidence):
            self.assertEqual(9, result.returncode, result.stderr)
            self.assertTrue((evidence / 'native-preflight-results/TEST-fixture.xml').is_file())
            self.assertTrue((evidence / 'recorder-sequences-results/TEST-fixture.xml').is_file())
            self.assertEqual('9', (evidence / 'recorder-sequences-gradle-exit.txt').read_text().strip())
            self.assertEqual('NATIVE\nRECORDER\n', (root / 'invocations.txt').read_text())
            self.assert_final_evidence(evidence, 9)

    def test_gradle_failure_code_wins_when_no_xml_was_produced(self):
        with self.fixture(FAKE_NATIVE_EXIT='7', FAKE_NATIVE_MODE='missing') as (result, root, evidence):
            self.assertEqual(7, result.returncode, result.stderr)
            self.assertTrue((evidence / 'native-preflight-results/missing-results.txt').is_file())
            self.assertEqual('1', (evidence / 'native-preflight-evidence-exit.txt').read_text().strip())
            self.assert_final_evidence(evidence, 7)

    def test_abi_failure_has_terminal_status_and_logcat_without_gradle(self):
        with self.fixture(FAKE_ABIS='x86_64', FAKE_LOGCAT_EXIT='17') as (result, root, evidence):
            self.assertEqual(3, result.returncode, result.stderr)
            self.assertFalse((root / 'invocations.txt').exists())
            self.assert_final_evidence(evidence, 3)

    def test_zero_gradle_exit_cannot_hide_missing_failed_or_skipped_cases(self):
        for mode in ('missing', 'failure', 'error', 'skipped'):
            with self.subTest(mode=mode), self.fixture(FAKE_RECORDER_MODE=mode) as (result, root, evidence):
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual('0', (evidence / 'recorder-sequences-gradle-exit.txt').read_text().strip())
                if mode == 'missing':
                    self.assertTrue((evidence / 'recorder-sequences-results/missing-results.txt').is_file())
                else:
                    cases = list(ET.parse(evidence / 'recorder-sequences-results/TEST-fixture.xml').getroot().iter('testcase'))
                    self.assertEqual(3, len(cases))
                self.assert_final_evidence(evidence, result.returncode)

    def test_missing_or_split_runtime_apk_identity_is_not_a_pass(self):
        for mode in ('missing', 'split'):
            with self.subTest(mode=mode), self.fixture(FAKE_APK_MODE=mode) as (result, root, evidence):
                self.assertEqual(4, result.returncode, result.stdout + result.stderr)
                self.assertEqual('4', (evidence / 'native-preflight-installed-exit.txt').read_text().strip())
                self.assert_final_evidence(evidence, 4)
        with self.fixture(FAKE_APK_MODE='missing', FAKE_NATIVE_EXIT='7') as (result, root, evidence):
            self.assertEqual(7, result.returncode)
            self.assert_final_evidence(evidence, 7)

    def test_success_keeps_phase_results_and_actual_stdout_bytes(self):
        with self.fixture() as (result, root, evidence):
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            for phase in ('native-preflight', 'recorder-sequences'):
                self.assertTrue((evidence / (phase + '-results/TEST-fixture.xml')).is_file())
                for package in ('com.hatsunama.captionaction.debug', 'com.hatsunama.captionaction.debug.test'):
                    apk = evidence / (phase + '-installed') / (package + '.apk')
                    self.assertEqual(('installed /data/app/' + package + '/base.apk').encode(), apk.read_bytes())
                    self.assertIn('certificate SHA-256 digest', apk.with_suffix('.signing.txt').read_text())
                self.assertTrue((evidence / (phase + '-installed/SHA256SUMS')).is_file())
                self.assertEqual('0', (evidence / (phase + '-gradle-exit.txt')).read_text().strip())
            self.assertEqual('verified fixture 🌏\n'.encode(), (evidence / 'first-session.txt').read_bytes())
            self.assertTrue((evidence / 'transcript-SHA256SUMS').is_file())
            self.assert_final_evidence(evidence, 0)

if __name__ == '__main__':
    unittest.main(verbosity=2)
