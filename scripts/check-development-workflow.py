#!/usr/bin/env python3
"""Regression assertions for this repository's development/release CI contract."""
from pathlib import Path
import re
import unittest
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = Path(os.environ.get('CAPTION_WORKFLOW_CONFIG', ROOT / '.github/workflows/android-checks.yml')).read_text()
JOB_TEXT = WORKFLOW.split('\njobs:\n', 1)[1]
JOBS = {m.group(1): m.group(2) for m in re.finditer(r'^  ([\w-]+):\n(.*?)(?=^  [\w-]+:\n|\Z)', JOB_TEXT, re.M | re.S)}

class DevelopmentWorkflowTest(unittest.TestCase):
    def test_hosted_sdk_tools_work_without_preexisting_path(self):
        for name, body in JOBS.items():
            match = re.search(r'      - name: Reuse hosted SDK.*?        run: \|\n(.*?)(?=      - |\Z)', body, re.S)
            self.assertIsNotNone(match, name)
            script = ''.join(line[10:] + '\n' for line in match[1].splitlines())
            for installed in (True, False):
                with self.subTest(job=name, installed=installed), tempfile.TemporaryDirectory() as directory:
                    folder = Path(directory)
                    sdk = folder / 'sdk'
                    tool_dir = sdk / 'cmdline-tools/latest/bin'
                    tool_dir.mkdir(parents=True)
                    tool = tool_dir / 'sdkmanager'
                    tool.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$SDK_CALLS"\nif [[ "$*" != *--list_installed* ]]; then test -z "$(cat)"; fi\n')
                    tool.chmod(0o755)
                    if installed:
                        jar = sdk / 'platforms/android-36/android.jar'
                        jar.parent.mkdir(parents=True)
                        jar.touch()
                        aapt = sdk / 'build-tools/35.0.0/aapt2'
                        aapt.parent.mkdir(parents=True)
                        aapt.touch()
                        aapt.chmod(0o755)
                    env = dict(os.environ, PATH='/usr/bin:/bin', ANDROID_HOME=str(sdk),
                               GITHUB_ENV=str(folder / 'env'), GITHUB_PATH=str(folder / 'path'),
                               SDK_CALLS=str(folder / 'calls'))
                    result = subprocess.run(['bash', '-c', script], cwd=folder, env=env,
                                            capture_output=True, text=True, timeout=5)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    calls = (folder / 'calls').read_text().splitlines()
                    self.assertEqual(1 if installed else 2, len(calls))
                    self.assertIn('--list_installed', calls[-1])
                    if not installed:
                        self.assertIn('platforms;android-36 build-tools;35.0.0', calls[0])
                    self.assertIn(str(tool_dir), (folder / 'path').read_text())
                    self.assertIn(str(sdk / 'platform-tools'), (folder / 'path').read_text())

    def test_software_emulation_is_explicit_bounded_and_records_runtime_identity(self):
        body = JOBS['emulator-recorder']
        self.assertIn('ANDROID_I_WANT_MY_TCG=yes', body)
        self.assertIn('-accel "$EMULATOR_ACCEL"', body)
        self.assertIn('unsupported slow experimental software emulation', body)
        self.assertIn('SECONDS + 600', body)
        self.assertIn('timeout --signal=TERM --kill-after=30s 1200', body)
        self.assertIn('emulator-acceleration.txt', body)
        self.assertNotRegex(body, r'(?m)^\s+(?:sudo )?(chmod|chown|usermod) ')
        self.assertEqual(['apt-get update', 'apt-get install -y --no-install-recommends libpulse0'], re.findall(r'^\s+sudo (.*)$', body, re.M))
        self.assertIn('-version', body)
        self.assertIn('env -u LD_LIBRARY_PATH \"$ANDROID_HOME/emulator/emulator\" -version', body)

    def test_acceleration_selection_records_both_modes_without_changing_permissions(self):
        match = re.search(r'      - name: Select existing acceleration.*?        run: \|\n(.*?)(?=      - |\Z)', JOBS['emulator-recorder'], re.S)
        self.assertIsNotNone(match)
        script = ''.join(line[10:] + '\n' for line in match[1].splitlines())
        for available in ('0', '1'):
            with self.subTest(kvm=available), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'ci-provenance').mkdir()
                env = dict(os.environ, GITHUB_ENV=str(root / 'env'), FAKE_KVM=available)
                boundary = 'test() { if [[ "$2" == /dev/kvm ]]; then [[ "$FAKE_KVM" == 1 ]]; else builtin test "$@"; fi; };\n'
                result = subprocess.run(['bash', '-c', boundary + script], cwd=root, env=env, capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                settings = (root / 'env').read_text()
                self.assertIn('EMULATOR_ACCEL=' + ('on' if available == '1' else 'off'), settings)
                self.assertEqual(available == '0', 'ANDROID_I_WANT_MY_TCG=yes' in settings)
                self.assertTrue((root / 'ci-provenance/emulator-acceleration.txt').is_file())

    def test_emulator_runtime_installs_only_missing_client_library_and_checks_version(self):
        match = re.search(r'      - name: Verify emulator host runtime.*?        run: \|\n(.*?)(?=      - |\Z)', JOBS['emulator-recorder'], re.S)
        if match is None:
            match = re.search(r'      - name: Boot compatible emulator.*?        run: \|\n(.*?)(?=      - |\Z)', JOBS['emulator-recorder'], re.S)
        self.assertIsNotNone(match)
        script = ''.join(line[10:] + '\n' for line in match[1].splitlines())
        for installed in (('0', '1') if 'Verify emulator host runtime' in JOBS['emulator-recorder'] else ('0',)):
            with self.subTest(installed=installed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                binaries = root / 'bin'
                binaries.mkdir()
                (root / 'ci-provenance').mkdir()
                (root / 'ci-logs').mkdir()
                emulator = root / 'sdk/emulator/emulator'
                emulator.parent.mkdir(parents=True)
                programs = {
                    'sudo': '#!/bin/sh\nexec "$@"\n',
                    'apt-get': '#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\ncase "$*" in *install*) touch "$READY";; esac\n',
                    'dpkg-query': '#!/bin/sh\nif test -f "$READY"; then echo installed; else exit 1; fi\n',
                    'ldd': '#!/bin/sh\nif test -f "$READY"; then echo "libpulse.so.0 => /lib/libpulse.so.0"; else echo "libpulse.so.0 => not found"; fi\n',
                }
                for name, source in programs.items():
                    path = binaries / name
                    path.write_text(source)
                    path.chmod(0o755)
                emulator.write_text('#!/bin/sh\ntest -f "$READY" || exit 127\necho "fixture emulator version"\n')
                emulator.chmod(0o755)
                if installed == '1': (root / 'ready').touch()
                env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                           ANDROID_HOME=str(root / 'sdk'), READY=str(root / 'ready'), CALLS=str(root / 'calls'))
                result = subprocess.run(['bash', '-c', script], cwd=root, env=env, capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('fixture emulator version', (root / 'ci-provenance/emulator-version.txt').read_text())
                self.assertNotIn('not found', (root / 'ci-provenance/emulator-ldd-after.txt').read_text())
                if installed == '0':
                    self.assertEqual(['update', 'install -y --no-install-recommends libpulse0'], (root / 'calls').read_text().splitlines())
                    self.assertIn('not found', (root / 'ci-provenance/emulator-ldd-before.txt').read_text())
                else:
                    self.assertFalse((root / 'calls').exists())

    def test_boot_shares_avd_home_and_detects_early_emulator_exit(self):
        match = re.search(r'      - name: Boot compatible emulator.*?        run: \|\n(.*?)(?=      - |\Z)', JOBS['emulator-recorder'], re.S)
        self.assertIsNotNone(match)
        script = ''.join(line[10:] + '\n' for line in match[1].splitlines())
        for mode in ('success', 'early-exit', 'missing-ini'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for name in ('bin', 'home', 'xdg', 'ci-logs', 'ci-provenance', 'scripts', 'sdk/emulator', 'run'):
                    (root / name).mkdir(parents=True)
                programs = {
                    'avdmanager': '''#!/usr/bin/env python3
import os,pathlib,sys
home=pathlib.Path(os.environ.get('ANDROID_AVD_HOME',os.environ['XDG_CONFIG_HOME']+'/.android/avd'))
if 'create' in sys.argv:
 home.mkdir(parents=True,exist_ok=True)
 (home/'caption-ci.avd').mkdir(exist_ok=True)
 (home/'caption-ci.avd/config.ini').write_text('fixture config')
 if os.environ['BOOT_MODE']!='missing-ini':(home/'caption-ci.ini').write_text('path='+str(home/'caption-ci.avd')+'\\n')
else:print('Name: caption-ci\\nPath: '+str(home/'caption-ci.avd'))
''',
                    'adb': '''#!/usr/bin/env python3
import os,sys
if 'get-state' in sys.argv:
 if os.environ['BOOT_MODE']=='early-exit':sys.exit(1)
 print('device')
elif 'getprop' in sys.argv:print('1')
''',
                    'timeout': '#!/bin/sh\nif test "$1" = 600; then exit 124; fi\nwhile test "${1#-}" != "$1"; do shift; done\nshift\nexec "$@"\n',
                    'sleep': '#!/bin/sh\n/bin/sleep 0.1\n',
                }
                for name, source in programs.items():
                    path = root / 'bin' / name
                    path.write_text(source)
                    path.chmod(0o755)
                emulator = root / 'sdk/emulator/emulator'
                emulator.write_text('''#!/usr/bin/env python3
import os,pathlib,sys,time
home=pathlib.Path(os.environ.get('ANDROID_AVD_HOME',os.environ['HOME']+'/.android/avd'))
if '-list-avds' in sys.argv:
 if (home/'caption-ci.ini').exists():print('caption-ci')
 sys.exit(0)
pathlib.Path('launched').touch()
if not (home/'caption-ci.ini').exists() or os.environ['BOOT_MODE']=='early-exit':sys.exit(17)
while True:time.sleep(1)
''')
                emulator.chmod(0o755)
                (root / 'scripts/run-recorder-emulator-check.sh').write_text('touch helper-called\n')
                env = dict(os.environ, PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
                           ANDROID_HOME=str(root / 'sdk'), RUNNER_TEMP=str(root / 'run'), HOME=str(root / 'home'),
                           XDG_CONFIG_HOME=str(root / 'xdg'), EMULATOR_ACCEL='off', BOOT_MODE=mode)
                for key in ('ANDROID_AVD_HOME', 'ANDROID_USER_HOME', 'ANDROID_EMULATOR_HOME'):
                    env.pop(key, None)
                result = subprocess.run(['bash', '-c', script], cwd=root, env=env, capture_output=True, text=True, timeout=5)
                if mode == 'success':
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertTrue((root / 'helper-called').exists())
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotEqual(124, result.returncode, 'Must detect invalid AVD/owned-process exit, not wait for timeout')
                    self.assertFalse((root / 'helper-called').exists())
                    if mode == 'missing-ini': self.assertFalse((root / 'launched').exists())

    def test_unsigned_artifact_gate_precedes_upload_and_checkpoint_setup(self):
        body = JOBS['development-checks']
        self.assertIn(':app:testUnsignedBuildGuard', body)
        self.assertIn('python3 scripts/test-unsigned-artifacts.py', body)
        signature = body.index('python3 scripts/verify-unsigned-apks.py')
        upload = body.index('name: Preserve development and instrumentation APKs')
        self.assertLess(signature, upload)
        command = body[signature:].splitlines()[0]
        self.assertIn('app/build/outputs/apk/debug/*.apk', command)
        self.assertIn('app/build/outputs/apk/androidTest/debug/*.apk', command)
        release = JOBS['final-release-artifacts']
        self.assertLess(release.index('scripts/verify-unsigned-apks.py'), release.index('name: Preserve unsigned artifacts'))
        self.assertLess(release.index('scripts/verify-unsigned-aabs.py'), release.index('name: Preserve unsigned artifacts'))
        emulator = JOBS['emulator-recorder']
        block = emulator.index('run: bash scripts/require-c-signed-emulator-artifacts.sh')
        self.assertLess(block, emulator.index('uses: actions/setup-java'))
        result = subprocess.run(['bash', str(ROOT / 'scripts/require-c-signed-emulator-artifacts.sh')], capture_output=True, text=True)
        self.assertEqual(4, result.returncode)
        self.assertIn('C-signed', result.stderr)

    def test_default_job_builds_only_development_apk(self):
        body = JOBS.get('development-checks', JOBS.get('unsigned-build', ''))
        tasks = set(re.findall(r':app:[A-Za-z]+', body))
        self.assertNotIn(':app:preparePlayAab', tasks, 'Normal development runs must not generate an AAB')
        self.assertNotIn(':app:prepareSolanaApk', tasks, 'Normal development runs must not package release APKs')
        self.assertTrue({':app:compileDebugKotlin', ':app:testDebugUnitTest', ':app:testReleaseUnitTest', ':app:lintDebug', ':app:lintRelease', ':app:assembleDebug', ':app:assembleDebugAndroidTest'} <= tasks)
        self.assertIn('app/build/outputs/apk/debug/', body)
        self.assertIn('app/build/outputs/apk/androidTest/debug/*.apk', body)
        self.assertIn('name: caption-development-apks', body)
        self.assertIn('if-no-files-found: error', body)
        self.assertNotRegex(body, r'(?m)^    if:', 'Normal development checks must stay unconditional')

    def test_release_artifacts_require_deliberate_checkpoint(self):
        self.assertRegex(WORKFLOW, r'release_checkpoint:\n\s+description:.*\n\s+type: boolean\n\s+default: false')
        release = JOBS.get('final-release-artifacts', '')
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.release_checkpoint", release)
        self.assertIn('needs: development-checks', release)
        self.assertIn(':app:preparePlayAab', release)
        self.assertIn(':app:prepareSolanaApk', release)

    def test_both_jobs_checkout_record_and_verify_exact_candidate(self):
        for name in ('development-checks', 'final-release-artifacts', 'emulator-recorder'):
            with self.subTest(job=name):
                body = JOBS.get(name, '')
                self.assertIn('ref: ${{ github.event.pull_request.head.sha || github.sha }}', body)
                self.assertIn('persist-credentials: false', body)
                self.assertIn('git rev-parse HEAD', body)
                self.assertIn('TESTED_SHA', body)
                self.assertIn('EXPECTED_SHA', body)
        self.assertIn('if: always()', JOBS.get('development-checks', ''))
        self.assertIn('app/build/test-results/', JOBS.get('development-checks', ''))

    def test_emulator_checkpoint_requires_explicit_manual_opt_in(self):
        emulator = JOBS['emulator-recorder']
        guard = re.search(r'(?m)^    if: (.+)$', emulator)
        self.assertIsNotNone(guard)
        self.assertEqual(
            "${{ github.event_name == 'workflow_dispatch' && inputs.emulator_checkpoint }}",
            guard[1],
            'Branch updates must not start the experimental emulator checkpoint',
        )
        self.assertRegex(WORKFLOW, r'emulator_checkpoint:\n\s+description:.*experimental.*\n\s+type: boolean\n\s+default: false')

    def test_manual_emulator_checkpoint_keeps_failures_visible(self):
        emulator = JOBS['emulator-recorder']
        self.assertNotIn('continue-on-error:', WORKFLOW)
        self.assertRegex(emulator, r'(?m)^          ANDROID_SERIAL=emulator-5554 timeout --signal=TERM --kill-after=30s 1200 bash scripts/run-recorder-emulator-check\.sh$')
        self.assertIn('set -euo pipefail', emulator)
        self.assertIn('if: always()', emulator)
        self.assertIn('app/build/outputs/androidTest-results/', emulator)

    def test_emulator_checkpoint_requires_real_tests_and_no_license_sweep(self):
        emulator = JOBS.get('emulator-recorder', '')
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.emulator_checkpoint", emulator)
        self.assertIn('needs: development-checks', emulator)
        self.assertIn('bash scripts/run-recorder-emulator-check.sh', emulator)
        self.assertIn('test -r /dev/kvm && test -w /dev/kvm', emulator)
        self.assertIn('ci-emulator-evidence/', emulator)
        self.assertNotIn('sdkmanager --licenses', WORKFLOW)
        self.assertIn("missing+=('system-images;android-35;google_apis;x86_64')", emulator)
        self.assertIn('sdkmanager "${missing[@]}" < /dev/null', emulator)
        self.assertNotIn('24333f8a63b6825ea9c5514f83c2829b004d1fee', WORKFLOW)
        self.assertNotRegex(WORKFLOW, r'uses: [^\n]+@v[0-9]')

class EmulatorBoundaryTest(unittest.TestCase):
    def test_physical_or_mixed_device_targets_never_reach_gradle(self):
        script = ROOT / 'scripts/run-recorder-emulator-check.sh'
        self.assertTrue(script.is_file(), 'Need an emulator-only execution boundary before CI use')
        for serial, listing in [('physical-123', 'physical-123\tdevice'), ('emulator-5554', 'emulator-5554\tdevice\nphysical-123\tdevice')]:
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                adb = folder / 'adb'
                adb.write_text('#!/bin/sh\nprintf "List of devices attached\\n%s\\n" "$FAKE_DEVICES"\n')
                adb.chmod(0o755)
                called = folder / 'gradle-called'
                gradle = folder / 'fake-gradle'
                gradle.write_text('#!/bin/sh\ntouch "' + str(called) + '"\n')
                gradle.chmod(0o755)
                env = dict(os.environ, PATH=directory + os.pathsep + os.environ['PATH'], ANDROID_SERIAL=serial, FAKE_DEVICES=listing, GRADLE_BIN=str(gradle))
                result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, timeout=5)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(called.exists(), result.stdout + result.stderr)

if __name__ == '__main__':
    unittest.main(verbosity=2)
