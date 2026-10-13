#!/usr/bin/env python3
"""Offline tests only. No SDK tools, downloads, adb, emulator, or credentials."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
import boot_probe as probe
probe.select_cell("api36")


class Guards(unittest.TestCase):
    def env(self):
        return dict(PROBE_REPOSITORY_PRIVATE='false', PROBE_REPOSITORY_VISIBILITY='public',
                    GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted',
                    GITHUB_REPOSITORY='xMilo-At-Your-Side/Caption-Action-build', GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/main',
                    PROBE_SOURCE_REPOSITORY='Hatsunama/Caption-Action',
                    PROBE_EXECUTION_COMMIT='b' * 40, PROBE_APPROVED_EXECUTION_COMMIT='b' * 40, GITHUB_SHA='b' * 40,
                    ImageOS='ubuntu24', RUNNER_OS='Linux', RUNNER_ARCH='X64',
                    PROBE_COMMIT='a' * 40)

    def test_public_exact_standard_context(self):
        probe.policy(self.env())

    def test_policy_rejects_each_unsafe_or_unknown_value(self):
        for key in self.env():
            for value in ('', 'unexpected'):
                with self.subTest(key=key, value=value):
                    env = self.env()
                    env[key] = value
                    with self.assertRaises(probe.Blocked):
                        probe.policy(env)
        env = self.env()
        env['PROBE_REPOSITORY_PRIVATE'] = 'true'
        with self.assertRaisesRegex(probe.Blocked, 'public-repository'):
            probe.policy(env)

    def test_source_and_execution_commits_cannot_be_substituted(self):
        env=self.env();env['PROBE_COMMIT']=env['GITHUB_SHA']
        with self.assertRaises(probe.Blocked):probe.policy(env)
        env=self.env();env['PROBE_EXECUTION_COMMIT']='c'*40
        with self.assertRaises(probe.Blocked):probe.policy(env)

    def test_dispatch_requires_exact_reviewed_execution_commit(self):
        for value in ('', 'c'*40, 'b'*39, 'B'*40):
            env=self.env();env['PROBE_APPROVED_EXECUTION_COMMIT']=value
            with self.subTest(value=value),self.assertRaises(probe.Blocked):probe.policy(env)

    def test_personal_repo_push_is_not_an_org_manual_run(self):
        for key,value in [('GITHUB_REPOSITORY','Hatsunama/Caption-Action'),('GITHUB_EVENT_NAME','push'),('GITHUB_REF','refs/heads/probe/caption-muted-runtime-20261013'),('PROBE_SOURCE_REPOSITORY','xMilo-At-Your-Side/Caption-Action-build')]:
            env=self.env();env[key]=value
            with self.subTest(key=key),self.assertRaises(probe.Blocked):probe.policy(env)

    def test_unknown_cell_rejected(self):
        with self.assertRaisesRegex(probe.Blocked, 'UNKNOWN_CELL'):
            probe.select_cell('paid-runner')

    def test_package_listing_exact_and_available_only(self):
        good = 'Available Packages:\n' + ''.join(f'{k} | {v} | description\n' for k, v in probe.PACKAGES.items())
        self.assertEqual(probe.parse_available(good), probe.PACKAGES)
        for text in (good.replace('37.2.12', '37.2.13'), good.replace('Available Packages:', 'Installed packages:'),
                     good.replace('google_apis', 'google_apis_ps16k'), good + 'emulator | 37.2.12 | duplicate\n', ''):
            with self.assertRaises(probe.Blocked):
                probe.parse_available(text)

    def facts(self):
        return dict(boot_completed='1', page_size='4096', sdk='36', abi='x86_64',
                    abilist='x86_64', fingerprint='google/test-device/test:15/test/userdebug')

    def test_actual_facts_required(self):
        probe.validate_runtime(self.facts())
        for key in self.facts():
            facts = self.facts()
            del facts[key]
            with self.subTest(key=key), self.assertRaises(probe.Blocked):
                probe.validate_runtime(facts)
        facts = self.facts()
        facts['page_size'] = '16384'
        with self.assertRaisesRegex(probe.Blocked, 'page_size'):
            probe.validate_runtime(facts)

    def test_missing_license_markers_stop_before_tools(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            cmd = root / 'sdk/cmdline-tools/latest/bin'
            cmd.mkdir(parents=True)
            for name in ('sdkmanager', 'avdmanager'):
                (cmd / name).write_text('fixture')
            evidence = root / 'evidence'
            evidence.mkdir()
            p = probe.Probe(evidence, root)
            with patch.dict(os.environ, {'ANDROID_SDK_ROOT': str(root / 'sdk')}), patch.object(p, 'spawn') as spawn:
                with self.assertRaisesRegex(probe.Blocked, 'LICENSE_BLOCKER'):
                    p.execute()
                spawn.assert_not_called()

    def test_bounded_process_and_failure_capture(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            p = probe.Probe(root, root)
            # Only tiny, local Python child processes exercise watchdog and cleanup.
            with self.assertRaisesRegex(probe.Blocked, 'COMMAND_TIMEOUT'):
                p.run([sys.executable, '-c', 'import time; time.sleep(60)'], 'sleep.txt', seconds=.1)
            self.assertFalse(p.children)
            with self.assertRaisesRegex(probe.Blocked, 'COMMAND_FAILED'):
                p.run([sys.executable, '-c', 'raise SystemExit(7)'], 'failure.txt')
            self.assertEqual(p.run([sys.executable, '-c', 'print(1); raise SystemExit(1)'], 'false-boot.txt', allow_failure=True), '')
            self.assertTrue(p.cleanup())
            self.assertNotEqual(p.result['status'], 'environment-boot-verified')

    def test_allowed_transient_command_timeout_is_empty_not_boot_success(self):
        with tempfile.TemporaryDirectory() as td:
            p = probe.Probe(Path(td), Path(td))
            value = p.run([sys.executable, '-c', 'import time; time.sleep(60)'],
                          'slow-adb.txt', seconds=.1, allow_failure=True)
            self.assertEqual(value, '')
            self.assertFalse(p.children)
            self.assertNotEqual(p.result['status'], 'environment-boot-verified')
            self.assertIn('transient-command-timeout', (Path(td) / 'commands.jsonl').read_text())
            p.cleanup()

    def test_allowed_failure_does_not_relax_total_deadline_or_log_limit(self):
        for boundary in ('deadline', 'log-limit'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as td:
                p = probe.Probe(Path(td), Path(td))
                if boundary == 'deadline':
                    p.deadline = probe.time.monotonic() + .1
                    command = 'import time; time.sleep(60)'
                    expected = 'TOTAL_DEADLINE'
                else:
                    command = 'import sys,time; sys.stdout.write("x" * 100); sys.stdout.flush(); time.sleep(60)'
                    expected = 'COMMAND_LOG_LIMIT'
                with patch.object(probe, 'LOG_LIMIT', 10), self.assertRaisesRegex(probe.Blocked, expected):
                    p.run([sys.executable, '-c', command], 'bounded.txt', seconds=5, allow_failure=True)
                self.assertFalse(p.children)
                p.cleanup()

    def test_expired_deadline_does_not_spawn(self):
        with tempfile.TemporaryDirectory() as td:
            p = probe.Probe(Path(td), Path(td))
            p.deadline = 0
            with patch.object(p, 'spawn') as spawn, self.assertRaisesRegex(probe.Blocked, 'TOTAL_DEADLINE'):
                p.run(['never-run'], 'expired.txt', allow_failure=True)
            spawn.assert_not_called()

    def test_uncertain_interrupted_acl_grant_restores_baseline(self):
        lease = probe.KvmLease()
        lease.uid = 1001
        info = Mock(st_mode=probe.stat.S_IFCHR | 0o660, st_uid=0, st_rdev=os.makedev(10, 232))
        baseline = 'user::rw-\ngroup::rw-\nother::---'
        with patch('boot_probe.Path.lstat', return_value=info), patch('boot_probe.os.access', return_value=False), \
             patch.object(lease, 'command', side_effect=[baseline, probe.Blocked('INTERRUPTED:15'), '', baseline]) as cmd:
            with self.assertRaisesRegex(probe.Blocked, 'INTERRUPTED'):
                lease.acquire()
            self.assertTrue(lease.changed)
            lease.release()
            self.assertEqual(cmd.call_args_list[2].args[0], ['sudo', '-n', 'setfacl', '--set', 'u::rw-,g::rw-,o::---', '/dev/kvm'])
            self.assertFalse(lease.changed)

    def test_only_official_ubuntu_dependency_downloads(self):
        for host in ('archive.ubuntu.com', 'security.ubuntu.com', 'azure.archive.ubuntu.com'):
            self.assertEqual(probe.verify_ubuntu_package_uris("'https://" + host + "/ubuntu/pool/main/p/pulseaudio/libpulse0.deb' x 123 SHA256:abc"), 1)
        for url in ('https://evil.example/ubuntu/pool/test.deb', 'https://archive.ubuntu.com.evil.test/ubuntu/pool/test.deb',
                    'https://user:password@archive.ubuntu.com/ubuntu/pool/test.deb', 'file:///tmp/test.deb'):
            with self.assertRaises(probe.Blocked):
                probe.verify_ubuntu_package_uris("'" + url + "' file 123 hash")
        with self.assertRaisesRegex(probe.Blocked, 'PLAN_MISSING'):
            probe.verify_ubuntu_package_uris('')

    def test_exact_runner_mirror_file_resolves_only_official_hosts(self):
        plan="'mirror+file:/etc/apt/apt-mirrors.txt/pool/main/p/pulseaudio/libpulse0.deb' file 123 hash"
        mirrors='http://azure.archive.ubuntu.com/ubuntu priority:1\nhttps://archive.ubuntu.com/ubuntu priority:2\n'
        self.assertEqual(probe.verify_ubuntu_package_uris(plan, mirrors), 1)
        for bad in ('https://evil.example/ubuntu', 'https://archive.ubuntu.com.evil.test/ubuntu',
                    'https://user:secret@archive.ubuntu.com/ubuntu', ''):
            with self.assertRaises(probe.Blocked):
                probe.verify_ubuntu_package_uris(plan, bad)
        for bad in (plan.replace('apt-mirrors.txt', 'other.txt'), plan.replace('/pool/', '/../pool/')):
            with self.assertRaises(probe.Blocked):
                probe.verify_ubuntu_package_uris(bad, mirrors)

    def test_adb_uses_supported_exclusively_loopback_listener(self):
        self.assertEqual(probe.adb_server_args('/sdk/adb'), ['/sdk/adb', '-L', 'tcp:localhost:5038', 'server', 'nodaemon'])
        good='header\n0: 0100007F:13AE 00000000:0000 0A'
        probe.verify_loopback_listener(good, 'header')
        for table4,table6 in [('header','header'), (good.replace('0100007F','00000000'),'header'),
                             (good,'header\n0: 00000000000000000000000000000000:13AE 0000:0000 0A')]:
            with self.assertRaisesRegex(probe.Blocked, 'LOOPBACK'):
                probe.verify_loopback_listener(table4,table6)
        source=Path(probe.__file__).read_text()
        self.assertIn("ADB_USB='0', ADB_MDNS='0'", source)
        self.assertNotIn('ADB_LIBUSB=', source)

    def test_missing_loader_library_is_safely_classified(self):
        with tempfile.TemporaryDirectory() as td:
            p = probe.Probe(Path(td), Path(td))
            with self.assertRaisesRegex(probe.Blocked, '^MISSING_HOST_LIBRARY:libpulse.so.0$'):
                p.run([sys.executable, '-c', "print('/private/path: error while loading shared libraries: libpulse.so.0: missing'); raise SystemExit(127)"], 'loader.txt')
            p.cleanup()

    def test_explicit_license_failure_classification(self):
        with tempfile.TemporaryDirectory() as td:
            p = probe.Probe(Path(td), Path(td))
            with self.assertRaisesRegex(probe.Blocked, 'LICENSE_BLOCKER'):
                p.run([sys.executable, '-c', "print('License not accepted'); raise SystemExit(1)"], 'license.txt')
            p.cleanup()

    def simulated_boot(self, page_size='4096', boot_seconds=60, gpu_help='software', transient_first_poll=False):
        # In-memory command doubles exercise the whole post-approval control flow.
        # No SDK executable, adb or emulator is invoked.
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            evidence = root / 'evidence'
            evidence.mkdir()
            p = probe.Probe(evidence, root)
            p.work = root / 'work'
            p.sdk = p.work / 'sdk'
            p.sdkmanager = 'fixture-sdkmanager'
            p.avdmanager = 'fixture-avdmanager'
            (p.sdk / 'licenses').mkdir(parents=True)
            p.write('runner-license-marker-hashes.json', '{}')
            (p.work / 'avd').mkdir()
            (p.work / 'avd/config.ini').write_text('')
            for name, rev in probe.PACKAGES.items():
                props = p.sdk.joinpath(*name.split(';')) / 'source.properties'
                props.parent.mkdir(parents=True, exist_ok=True)
                props.write_text('Pkg.Revision=' + rev + '\n')
            boot_polls = []
            def fake_run(args, name, **kwargs):
                if name == "boot-last.txt":
                    boot_polls.append(name)
                    if transient_first_poll and len(boot_polls) == 1:
                        return ""
                if name == 'available-packages.txt':
                    return 'Available Packages:\n' + ''.join(f'{k} | {v} | fixture\n' for k, v in probe.PACKAGES.items())
                return {'emulator-help-accel.txt': 'on', 'emulator-help-gpu.txt': gpu_help,
                        'emulator-help-all.txt': 'no-window no-audio no-snapshot camera-back camera-front port',
                        'boot-last.txt': '1', 'page-size.txt': page_size, 'sdk.txt': '36',
                        'abi.txt': 'x86_64', 'abilist.txt': 'x86_64', 'fingerprint.txt': 'fixture-fingerprint'}.get(name, '')
            def fake_spawn(args, name):
                path = evidence / name
                path.write_text('fixture log')
                return Mock(poll=Mock(return_value=None)), path
            with patch.object(p, 'setup'), patch.object(p, 'resources'), \
                 patch.object(p, 'run', side_effect=fake_run), patch.object(p, 'spawn', side_effect=fake_spawn), \
                 patch.object(probe, 'BOOT_SECONDS', boot_seconds), patch('boot_probe.time.sleep'), \
                 patch.object(probe, 'verify_loopback_listener'):
                try:
                    p.execute()
                    return p.result, None
                except probe.Blocked as e:
                    return p.result, str(e)

    def test_simulated_verified_boot(self):
        result, error = self.simulated_boot()
        self.assertIsNone(error)
        self.assertEqual(result['status'], 'environment-boot-verified')
        self.assertTrue(result['page_size_verified'])

    def test_simulated_transient_boot_poll_then_verified_boot(self):
        result, error = self.simulated_boot(transient_first_poll=True)
        self.assertIsNone(error)
        self.assertEqual(result["status"], "environment-boot-verified")

    def test_simulated_wrong_page_size_not_pass(self):
        result, error = self.simulated_boot(page_size='16384')
        self.assertIn('RUNTIME_MISMATCH:page_size', error)
        self.assertFalse(result['page_size_verified'])

    def test_simulated_boot_timeout_not_pass(self):
        result, error = self.simulated_boot(boot_seconds=0)
        self.assertIn('BOOT_TIMEOUT', error)
        self.assertNotEqual(result['status'], 'environment-boot-verified')

    def test_simulated_unsupported_cli_not_launched(self):
        result, error = self.simulated_boot(gpu_help='host only')
        self.assertEqual(error, 'CLI_MODES_NOT_ADVERTISED')
        self.assertFalse(result['page_size_verified'])

    def test_only_api36_actual_runtime_contract(self):
        try:
            for cell, (sdk, tag, revision, page) in probe.CELLS.items():
                probe.select_cell(cell)
                facts = self.facts()
                facts.update(sdk=sdk, page_size=page)
                probe.validate_runtime(facts)
                facts['page_size'] = '16384' if page == '4096' else '4096'
                with self.assertRaisesRegex(probe.Blocked, 'page_size'):
                    probe.validate_runtime(facts)
                self.assertEqual(len(probe.PACKAGES), 3)
        finally:
            probe.select_cell('api36')

    def test_kvm_grant_only_own_uid_then_exact_restore(self):
        lease = probe.KvmLease()
        lease.uid = 1001
        info = Mock(st_mode=probe.stat.S_IFCHR | 0o660, st_uid=0, st_rdev=os.makedev(10, 232))
        baseline = 'user::rw-\ngroup::rw-\nother::---'
        with patch('boot_probe.Path.lstat', return_value=info), patch('boot_probe.os.access', side_effect=[False, True]), \
             patch.object(lease, 'command', side_effect=[baseline, '', '', baseline]) as cmd:
            self.assertTrue(lease.acquire()['read_write'])
            lease.release()
            self.assertEqual(cmd.call_args_list[1].args[0], ['sudo', '-n', 'setfacl', '-m', 'u:1001:rw', '/dev/kvm'])
            self.assertEqual(cmd.call_args_list[2].args[0], ['sudo', '-n', 'setfacl', '--set', 'u::rw-,g::rw-,o::---', '/dev/kvm'])
            self.assertFalse(lease.changed)

    def test_unknown_kvm_acl_stops_without_change(self):
        lease = probe.KvmLease()
        lease.uid = 1001
        info = Mock(st_mode=probe.stat.S_IFCHR | 0o660, st_uid=0, st_rdev=os.makedev(10, 232))
        with patch('boot_probe.Path.lstat', return_value=info), patch.object(lease, 'command', return_value='user::rw-\nother::rw-') as cmd:
            with self.assertRaisesRegex(probe.Blocked, 'KVM_UNEXPECTED'):
                lease.acquire()
            self.assertEqual(cmd.call_count, 1)
            self.assertFalse(lease.changed)

    def test_wrong_kvm_device_stops_without_command(self):
        lease = probe.KvmLease()
        info = Mock(st_mode=probe.stat.S_IFREG | 0o660, st_uid=0, st_rdev=0)
        with patch('boot_probe.Path.lstat', return_value=info), patch.object(lease, 'command') as cmd:
            with self.assertRaisesRegex(probe.Blocked, 'KVM_DEVICE'):
                lease.acquire()
            cmd.assert_not_called()

if __name__ == '__main__':
    unittest.main(verbosity=2)
