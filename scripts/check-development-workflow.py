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

    def test_default_job_builds_only_development_apk(self):
        body = JOBS.get('development-checks', JOBS.get('unsigned-build', ''))
        tasks = set(re.findall(r':app:[A-Za-z]+', body))
        self.assertNotIn(':app:preparePlayAab', tasks, 'Normal development runs must not generate an AAB')
        self.assertNotIn(':app:prepareSolanaApk', tasks, 'Normal development runs must not package release APKs')
        self.assertTrue({':app:compileDebugKotlin', ':app:testDebugUnitTest', ':app:testReleaseUnitTest', ':app:lintDebug', ':app:lintRelease', ':app:assembleDebug'} <= tasks)
        self.assertIn('app/build/outputs/apk/debug/', body)

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

    def test_emulator_checkpoint_requires_real_tests_and_no_license_sweep(self):
        emulator = JOBS.get('emulator-recorder', '')
        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.emulator_checkpoint", emulator)
        self.assertIn('needs: development-checks', emulator)
        self.assertIn('bash scripts/run-recorder-emulator-check.sh', emulator)
        self.assertIn('test -r /dev/kvm && test -w /dev/kvm', emulator)
        self.assertIn('ci-emulator-evidence/', emulator)
        self.assertNotIn('sdkmanager --licenses', WORKFLOW)
        self.assertIn("missing+=('system-images;android-35;google_apis;x86_64')", emulator)
        self.assertIn("github.head_ref == 'fix/recorder-session-preservation'", emulator)
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
