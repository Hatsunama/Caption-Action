#!/usr/bin/env python3
"""Review-only, fail-closed Caption runtime. No signing/build/key creation paths."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import boot_probe as boot
import runtime_contract as contract
from runtime_contract import Blocked, PACKAGE, ACTIVITY, PORT, SERIAL, TAG
from safety_watchdog import (WATCHDOG_SECONDS, run_bounded, force_stop_verified,
                             owned_emulator_identity, destroy_owned_guest)
import artifact_gate

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def preflight(env, mode='capture'):
    boot.policy(env)
    boot.select_cell(env.get('PROBE_CELL', ''))
    # Both identity and exact approved UI must exist before SDK/apt/network work.
    artifact_gate.require_approved_identity(boot.PROBE_PARENT)
    if mode == 'capture':
        contract.require_ui_review()
    else:
        contract.require_discovery_review(mode, boot.PROBE_PARENT)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    parents = subprocess.check_output(['git', 'show', '-s', '--format=%P', 'HEAD'], text=True).strip()
    if actual != env.get('PROBE_COMMIT') or parents != boot.PROBE_PARENT:
        raise Blocked('EXACT_CHECKOUT_OR_DELIVERY_PARENT_MISMATCH')
    return True


class CaptureRun(boot.Probe):
    def __init__(self, evidence, temp_root):
        super().__init__(evidence, temp_root)
        self.result = {'status': 'blocked', 'scope': 'own-uid-platform-muted-capture',
                       'page_size_verified': False, 'runtime_pass': False}
        self.installed = False
        self.watchdog = None
        self.control = None
        self.capture_process = None
        self.guest_identity = None
        self.seq = 0
        self.verified_stopped = False

    def command(self, argv, *, timeout=5, max_output_bytes=262144):
        # Main-thread command bounds never extend the independent watchdog.
        return run_bounded(argv, timeout=self.remaining(timeout), max_output_bytes=max_output_bytes, env=self.env)

    def adb_command(self, args, seconds=5, allowed_codes=(0,)):
        if self.server.poll() is not None or self.emulator.poll() is not None:
            raise Blocked('OWNED_ADB_OR_EMULATOR_EXITED')
        result = self.command([self.adb, '-P', PORT, '-s', SERIAL, *args], timeout=seconds)
        if result.returncode not in allowed_codes or result.stderr.strip():
            raise Blocked('SCOPED_ADB_COMMAND_FAILED')
        return result.stdout.replace('\r', '').strip()

    def shell(self, args, **kwargs):
        return self.adb_command(['shell', *args], **kwargs)

    def assert_idle(self):
        if contract.parse_volume(self.shell(['media', 'volume', '--stream', '3', '--get'])) != 0:
            raise Blocked('MEDIA_VOLUME_NOT_ZERO')
        contract.verify_projection_idle(self.shell(['dumpsys', 'media_projection']))
        contract.verify_audio_idle(self.shell(['dumpsys', 'audio']),
                                   self.shell(['dumpsys', 'media.audio_policy']),
                                   self.shell(['dumpsys', 'media_session']))

    def snapshot_ui(self):
        # Dump only the fresh disposable guest's installed hierarchy; no screenshots.
        name = '/data/local/tmp/' + self.work.name + '-ui.xml'
        self.shell(['uiautomator', 'dump', name], seconds=5)
        xml = self.adb_command(['exec-out', 'cat', name])
        self.shell(['rm', '-f', name])
        activity = self.shell(['dumpsys', 'activity', 'activities'])
        window = self.shell(['dumpsys', 'window', 'windows'])
        return xml, activity, window

    def tap(self, spec):
        xml, activity, window = self.snapshot_ui()
        point = contract.unique_tap(xml, activity, window, spec)
        # A second ownership check immediately before the one exact-state tap.
        contract.foreground(self.shell(['dumpsys', 'activity', 'activities']),
                            self.shell(['dumpsys', 'window', 'windows']), spec.component)
        self.shell(['input', 'tap', *map(str, point)], seconds=2)

    def arm_watchdog(self):
        self.guest_identity = owned_emulator_identity(self.emulator.pid)
        read_fd, self.control = os.pipe()
        deadline = time.monotonic() + WATCHDOG_SECONDS
        self.watchdog_receipt = self.work / 'watchdog-receipt.json'
        ready = self.work / 'watchdog-ready.txt'
        args = [sys.executable, str(HERE / 'safety_watchdog.py'), '--fd', str(read_fd),
                '--deadline', str(deadline), '--adb', self.adb,
                '--identity', json.dumps(self.guest_identity),
                '--receipt', str(self.watchdog_receipt), '--ready', str(ready)]
        try:
            self.watchdog = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                            stderr=subprocess.DEVNULL, env=self.env,
                                            pass_fds=(read_fd,), start_new_session=True)
            self.children.append(self.watchdog)
        finally:
            os.close(read_fd)
        end = time.monotonic() + 2
        while time.monotonic() < end:
            if self.watchdog.poll() is not None:
                raise Blocked('WATCHDOG_DID_NOT_ARM')
            if ready.is_file() and ready.read_text() == 'armed\n':
                return
            time.sleep(.03)
        raise Blocked('WATCHDOG_READY_TIMEOUT')

    def force_stopped(self):
        force_stop_verified(self.adb, runner=self.command)
        contract.verify_service_stopped(self.shell(['dumpsys', 'activity', 'services', PACKAGE]))
        self.assert_idle()
        self.verified_stopped = True

    def disarm_watchdog(self):
        if not self.verified_stopped or self.watchdog.poll() is not None:
            raise Blocked('WATCHDOG_DISARM_WITHOUT_VERIFIED_STOP')
        os.write(self.control, b'D')
        os.close(self.control); self.control = None
        if self.watchdog.wait(timeout=2) != 0 or not self.watchdog_receipt.is_file():
            raise Blocked('WATCHDOG_ALREADY_FIRED_OR_UNCONFIRMED')
        if json.loads(self.watchdog_receipt.read_text()) != {'event': 'disarmed', 'stopped': True}:
            raise Blocked('WATCHDOG_DISARM_RECEIPT_INVALID')
        self.result['watchdog_disarmed_after_verified_stop'] = True

    def prepare_guest(self, artifact):
        super().execute()
        self.result['runtime_pass'] = False
        self.result['status'] = 'blocked'  # A boot-only result is never a capture pass.
        self.stage = 'guest-preconditions'
        locale = self.shell(['getprop', 'persist.sys.locale']) or self.shell(['getprop', 'ro.product.locale'])
        if locale != 'en-US' or self.shell(['wm', 'size']) != 'Physical size: 480x800':
            raise Blocked('UNREVIEWED_LOCALE_OR_DISPLAY')
        if self.shell(['pm', 'list', 'packages', PACKAGE]):
            raise Blocked('PROBE_ALREADY_INSTALLED_NOT_FRESH')
        contract.verify_volume_help(self.shell(['media', 'help']))
        contract.parse_volume(self.shell(['media', 'volume', '--stream', '3', '--get']))
        # The only system-volume mutation in this program is a decrease/set to zero.
        self.shell(['media', 'volume', '--stream', '3', '--set', '0'])
        self.assert_idle()
        artifact.recheck()
        self.stage = 'install-exact-verified-probe'
        # Mark first, so uncertain install completion still gets scoped cleanup.
        self.installed = True
        if self.adb_command(['install', '--user', '0', artifact.apk_path], seconds=45) != 'Success':
            raise Blocked('INSTALL_NOT_VERIFIED')
        artifact.recheck()
        package = self.shell(['dumpsys', 'package', PACKAGE])
        uid = contract.verify_package(package, stopped=True, permission=False)
        self.shell(['am', 'start', '-W', '-n', ACTIVITY], seconds=8)
        pid = self.shell(['pidof', PACKAGE])
        if not re.fullmatch(r'[1-9][0-9]{1,6}', pid):
            raise Blocked('OWN_PID_NOT_UNIQUE')
        process = self.shell(['ps', '-A', '-o', 'UID,PID,NAME'])
        rows = [x.split() for x in process.splitlines()[1:] if x.split() and x.split()[-1] == PACKAGE]
        if rows != [[str(uid), pid, PACKAGE]]:
            raise Blocked('PID_PACKAGE_UID_IDENTITY_MISMATCH')
        return pid

    def execute_capture(self, artifact):
        pid = self.prepare_guest(artifact)
        self.stage = 'official-record-audio-consent'
        self.tap(contract.APP_BEGIN)
        self.tap(contract.RECORD_AUDIO_UI)
        contract.verify_package(self.shell(['dumpsys', 'package', PACKAGE]), permission=True)
        self.assert_idle()
        self.capture_process, log = self.spawn([self.adb, '-P', PORT, '-s', SERIAL,
            'logcat', '-b', 'main', '--pid=' + pid, '-v', 'raw', TAG + ':I', '*:S'], 'capture-scalars.txt')
        self.stage = 'official-single-app-projection-consent'
        self.arm_watchdog()  # Independent process is confirmed armed BEFORE second Begin.
        self.tap(contract.APP_BEGIN)
        for step in contract.PROJECTION_UI_STEPS:
            self.tap(step)
        self.stage = 'three-phase-metrics'
        while True:
            if self.watchdog.poll() is not None:
                raise Blocked('WATCHDOG_FIRED_OR_EXITED')
            if self.capture_process.poll() is not None:
                raise Blocked('SCALAR_COLLECTOR_EXITED')
            if log.stat().st_size > contract.CAPTURE_LIMIT:
                raise Blocked('CAPTURE_LOG_LIMIT')
            raw = log.read_text()
            complete_lines = raw[:raw.rfind('\n') + 1] if raw else ''
            partial = contract.parse_metrics(complete_lines, complete=False)
            if partial['final_verified']:
                break
            self.remaining(.1)
            time.sleep(.05)
        self.stage = 'independent-teardown-readback'
        # App native teardown, independent service/projection/audio check, then
        # force-stop + process/package/service/projection/audio verification again.
        contract.verify_service_stopped(self.shell(['dumpsys', 'activity', 'services', PACKAGE]))
        self.assert_idle()
        self.force_stopped()
        self.stop(self.capture_process)
        self.capture_process = None
        metrics = contract.parse_metrics(log.read_text())  # Includes all late messages.
        self.disarm_watchdog()
        if self.adb_command(['uninstall', PACKAGE], seconds=10) != 'Success':
            raise Blocked('OWN_PACKAGE_UNINSTALL_UNVERIFIED')
        if self.shell(['pm', 'list', 'packages', PACKAGE]):
            raise Blocked('OWN_PACKAGE_STILL_INSTALLED')
        self.installed = False
        self.result.update(metrics, status='capture-verified-pending-host-cleanup',
                           mediaIndex=0, ownUid=True, platformOnly=True, teardownVerified=True)

    def execute_discovery(self, artifact, mode):
        # Discovery never invokes execute_capture or any projection tap loop.
        contract.require_discovery_review(mode, boot.PROBE_PARENT)
        self.prepare_guest(artifact)
        self.result.update(scope='official-consent-ui-discovery-only', runtime_pass=False,
                           capture_started=False, record_audio_granted=False, projection_granted=False)
        self.stage = 'discover-record-audio-dialog'
        self.arm_watchdog()  # Bound first dialog inspection as well as any second Begin.
        self.tap(contract.APP_BEGIN)
        if mode == 'discover-projection':
            # Actual unique reviewed official RECORD_AUDIO selector only. The
            # unchanged app intentionally stays idle after this permission grant.
            contract.require_record_audio_review()
            self.tap(contract.RECORD_AUDIO_UI)
            contract.verify_package(self.shell(['dumpsys', 'package', PACKAGE]), permission=True)
            self.result['record_audio_granted'] = True
            self.assert_idle()
            self.stage = 'discover-media-projection-dialog'
            self.tap(contract.APP_BEGIN)
        self.result['consent_dialog'] = contract.inspect_consent_dialog(*self.snapshot_ui(), mode)
        if mode == 'discover-record':
            contract.verify_package(self.shell(['dumpsys', 'package', PACKAGE]), permission=False)
        # No projection consent/picker/control is ever tapped in either mode.
        # Verify no service/capture started, then stop exact app, read back all
        # idle state, disarm only after that proof and uninstall the exact probe.
        contract.verify_service_stopped(self.shell(['dumpsys', 'activity', 'services', PACKAGE]))
        self.force_stopped()
        self.disarm_watchdog()
        if self.adb_command(['uninstall', PACKAGE], seconds=10) != 'Success':
            raise Blocked('OWN_PACKAGE_UNINSTALL_UNVERIFIED')
        if self.shell(['pm', 'list', 'packages', PACKAGE]):
            raise Blocked('OWN_PACKAGE_STILL_INSTALLED')
        self.installed = False
        self.result.update(status='discovery-stopped-pending-host-cleanup',
                           discovery_mode=mode, teardownVerified=True)

    def cleanup(self):
        errors = []
        # A failed parser/consent never bypasses stop and owned-guest destruction.
        if self.installed:
            try:
                force_stop_verified(self.adb, runner=lambda a, **kw: run_bounded(a, env=self.env, **kw))
            except Exception:
                errors.append('FAILURE_FORCE_STOP_UNVERIFIED')
        if getattr(self, 'emulator', None) is not None:
            try:
                identity = self.guest_identity or owned_emulator_identity(self.emulator.pid)
                destroy_owned_guest(identity)
            except FileNotFoundError:
                pass
            except Exception:
                errors.append('OWN_GUEST_DESTROY_UNVERIFIED')
        if self.control is not None:
            os.close(self.control); self.control = None
        ok = super().cleanup()
        self.result['cleanup_errors'].extend(errors)
        if errors or not ok:
            self.result['status'] = 'cleanup-failed'
        elif self.result['status'] == 'discovery-stopped-pending-host-cleanup':
            self.result.update(status='DISCOVERY_STOPPED', runtime_pass=False, discovery_stopped_verified=True)
        elif self.result['status'] == 'capture-verified-pending-host-cleanup':
            self.result.update(status='PASS', runtime_pass=True)
        return ok and not errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--preflight-only', action='store_true')
    parser.add_argument('--mode', choices=('capture', *contract.DISCOVERY_MODES), default='capture')
    args = parser.parse_args()
    preflight(os.environ, args.mode)
    # Concrete artifact-gate invocation is pinned alongside the final reviewed
    # identity. No path/env download override is permitted by this staged module.
    artifact = artifact_gate.verify_artifacts(
        delivery_parent=boot.PROBE_PARENT,
        signed_apk=ROOT / 'tools/muted-capture-probe/artifacts/caption-muted-platform-probe-signed.apk',
        receipt_path=ROOT / 'tools/muted-capture-probe/SIGNING_RECEIPT.json',
        unsigned_apk=ROOT / 'tools/muted-capture-probe/artifacts/caption-muted-platform-probe-unsigned.apk',
        source_archive=ROOT / 'tools/muted-capture-probe/artifacts/caption-muted-platform-probe-source.tar.gz',
        android_sdk_root=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME', ''),
        runner=run_bounded)
    with artifact:
        if args.preflight_only:
            print(json.dumps({'preflight_verified': True, 'runtime_executed': False}))
            return 0
        temp_root = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
        evidence = Path(tempfile.mkdtemp(prefix='caption-muted-evidence-', dir=temp_root))
        run = CaptureRun(evidence, temp_root)
        def interrupted(signum, frame):
            raise Blocked('INTERRUPTED')
        signal.signal(signal.SIGINT, interrupted); signal.signal(signal.SIGTERM, interrupted)
        try:
            if args.mode == 'capture':
                run.execute_capture(artifact)
            else:
                run.execute_discovery(artifact, args.mode)
        except Exception as error:
            run.result.update(boundary=run.stage, reason=type(error).__name__)
            # Only bounded, controlled reason tokens may reach public job logs.
            if isinstance(error, Blocked):
                run.result['reason'] = str(error).split(':')[0][:100]
        finally:
            try:
                run.cleanup()
            except Exception:
                run.result.update(status='cleanup-failed', runtime_pass=False)
            shutil.rmtree(evidence)  # No UI/dumpsys/raw collector files persisted/uploaded.
        print(json.dumps(run.result, sort_keys=True))
        if args.mode in contract.DISCOVERY_MODES:
            return 0 if run.result.get('discovery_stopped_verified') and run.result['status'] == 'DISCOVERY_STOPPED' else 1
        return 0 if run.result.get('runtime_pass') and run.result['status'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (Blocked, artifact_gate.ArtifactGateError) as error:
        print(json.dumps({'status': 'blocked', 'reason': str(error).split(':')[0][:100], 'runtime_pass': False}))
        raise SystemExit(1)
