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
# Linear workflow revision parent, separate from the immutable signed delivery.
CHECKOUT_PARENT = '9460569997d1cbfb880a8d23bccebb380445121a'

# One finite diagnostic sequence. Only the sixth command mutates volume, once.
AUDIO_DIAGNOSTIC_COMMANDS = (
    ('shell', 'cmd', 'audio'),
    ('shell', 'cmd', 'audio', 'get-min-volume', '3'),
    ('shell', 'cmd', 'audio', 'get-max-volume', '3'),
    ('shell', 'cmd', 'audio', 'get-stream-volume', '3'),
    ('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get'),
    ('shell', 'cmd', 'audio', 'set-volume', '3', '0'),
    ('shell', 'cmd', 'audio', 'get-stream-volume', '3'),
    ('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get'),
)


# Diagnostics are fixed labels/scalars only, never guest output or arguments.
_ADB_FIXED_LABELS = {
    ('shell', 'getprop', 'persist.sys.locale'): 'read_locale',
    ('shell', 'getprop', 'ro.product.locale'): 'read_fallback_locale',
    ('shell', 'wm', 'size'): 'read_display_size',
    ('shell', 'pm', 'list', 'packages', PACKAGE): 'list_probe_package',
    ('shell', 'cmd', 'media_session'): 'media_session_help',
    ('shell', 'cmd', 'audio'): 'audio_help',
    ('shell', 'cmd', 'audio', 'get-min-volume', '3'): 'audio_volume_min',
    ('shell', 'cmd', 'audio', 'get-max-volume', '3'): 'audio_volume_max',
    ('shell', 'cmd', 'audio', 'get-stream-volume', '3'): 'audio_volume_read',
    ('shell', 'cmd', 'audio', 'set-volume', '3', '0'): 'audio_volume_zero',
    ('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get'): 'media_session_volume_read',
    ('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', '0'): 'media_session_volume_zero',
    ('shell', 'dumpsys', 'media_projection'): 'read_projection_state',
    ('shell', 'dumpsys', 'audio'): 'read_audio_state',
    ('shell', 'dumpsys', 'media.audio_policy'): 'read_audio_policy',
    ('shell', 'dumpsys', 'media_session'): 'read_media_sessions',
    ('shell', 'dumpsys', 'package', PACKAGE): 'read_probe_package',
    ('shell', 'am', 'start', '-W', '-n', ACTIVITY): 'start_probe_activity',
    ('shell', 'pidof', PACKAGE): 'read_probe_pid',
    ('shell', 'ps', '-A', '-o', 'UID,PID,NAME'): 'read_process_identity',
    ('shell', 'dumpsys', 'activity', 'services', PACKAGE): 'read_probe_service',
    ('shell', 'dumpsys', 'activity', 'activities'): 'read_foreground_activity',
    ('shell', 'dumpsys', 'window', 'windows'): 'read_foreground_window',
    ('uninstall', PACKAGE): 'uninstall_probe',
}
_ADB_DYNAMIC_SHAPES = {
    (('shell', 'uiautomator', 'dump'), 4): 'dump_ui_hierarchy',
    (('exec-out', 'cat'), 3): 'read_ui_hierarchy',
    (('shell', 'rm', '-f'), 4): 'remove_ui_hierarchy',
    (('shell', 'input', 'tap'), 5): 'tap_reviewed_ui',
    (('install', '--user', '0'), 4): 'install_probe',
}
ADB_COMMAND_LABELS = frozenset((*_ADB_FIXED_LABELS.values(), *_ADB_DYNAMIC_SHAPES.values(), 'unrecognized_command'))
PRECONDITION_COMMAND_LABELS = frozenset(('read_locale', 'read_fallback_locale', 'read_display_size',
    'list_probe_package', 'media_session_help', 'media_session_volume_read', 'media_session_volume_zero',
    'read_projection_state', 'read_audio_state', 'read_audio_policy', 'read_media_sessions',
    'audio_help', 'audio_volume_min', 'audio_volume_max', 'audio_volume_read', 'audio_volume_zero'))
COMMAND_DIAGNOSTIC_LIMIT = 24
ADB_FAILURE_CLASSES = frozenset(('command_missing', 'permission_denied', 'unknown_command',
                                 'unexpected_stderr', 'nonzero_exit'))


def safe_command_label(args):
    # Input data never becomes a label, including malformed or unknown commands.
    if not isinstance(args, (list, tuple)) or any(type(value) is not str for value in args):
        return 'unrecognized_command'
    command = tuple(args)
    if command in _ADB_FIXED_LABELS:
        return _ADB_FIXED_LABELS[command]
    for (prefix, count), label in _ADB_DYNAMIC_SHAPES.items():
        if len(command) == count and command[:len(prefix)] == prefix:
            return label
    return 'unrecognized_command'


def safe_command_metadata(args, result):
    return {'command_label': safe_command_label(args),
            'exit_code': result.returncode if type(result.returncode) is int and -255 <= result.returncode <= 255 else None,
            'stdout_bytes': len(result.stdout.encode('utf-8', errors='replace')),
            'stderr_bytes': len(result.stderr.encode('utf-8', errors='replace'))}


def safe_command_failure(args, result, allowed_codes=(0,)):
    text = (result.stdout + '\n' + result.stderr).lower()
    if result.returncode == 127 or re.search(r'(?:inaccessible or not found|command not found|: not found)(?:\s|$)', text):
        classification = 'command_missing'
    elif re.search(r'permission denied|permission denial|securityexception', text):
        classification = 'permission_denied'
    elif re.search(r'unknown (?:command|option)|unrecognized (?:command|option)', text):
        classification = 'unknown_command'
    elif result.returncode in allowed_codes and result.stderr:
        classification = 'unexpected_stderr'
    else:
        classification = 'nonzero_exit'
    return {**safe_command_metadata(args, result), 'classification': classification}


def preflight(env, mode='capture'):
    boot.policy(env)
    boot.select_cell(env.get('PROBE_CELL', ''))
    # Both identity and exact approved UI must exist before SDK/apt/network work.
    artifact_gate.require_approved_identity(boot.PROBE_PARENT)
    if mode == 'capture':
        contract.require_ui_review()
    elif mode == 'diagnose-volume':
        contract.require_audio_diagnostic_review(boot.PROBE_PARENT)
    else:
        contract.require_discovery_review(mode, boot.PROBE_PARENT)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    parents = subprocess.check_output(['git', 'show', '-s', '--format=%P', 'HEAD'], text=True).strip()
    if actual != env.get('PROBE_COMMIT') or parents != CHECKOUT_PARENT:
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
        self.audio_diagnostic_index = None

    def command(self, argv, *, timeout=5, max_output_bytes=262144):
        # Main-thread command bounds never extend the independent watchdog.
        return run_bounded(argv, timeout=self.remaining(timeout), max_output_bytes=max_output_bytes, env=self.env)

    def adb_command(self, args, seconds=5, allowed_codes=(0,)):
        if self.server.poll() is not None or self.emulator.poll() is not None:
            raise Blocked('OWNED_ADB_OR_EMULATOR_EXITED')
        self.result['last_command_label'] = safe_command_label(args)
        audio_command = args[:3] == ['shell', 'cmd', 'audio']
        if self.audio_diagnostic_index is not None:
            index = self.audio_diagnostic_index
            if index >= len(AUDIO_DIAGNOSTIC_COMMANDS) or tuple(args) != AUDIO_DIAGNOSTIC_COMMANDS[index]:
                raise Blocked('AUDIO_DIAGNOSTIC_COMMAND_ORDER')
            # Consume before dispatch: uncertain completion can never retry a write.
            self.audio_diagnostic_index += 1
        elif audio_command:
            raise Blocked('AUDIO_COMMAND_OUTSIDE_DIAGNOSTIC')
        audio_help = args == ['shell', 'cmd', 'audio']
        media_help = args == ['shell', 'cmd', 'media_session']
        media_volume = args in (
            ['shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get'],
            ['shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', '0'])
        if media_help and allowed_codes != (255,):
            raise Blocked('UNREVIEWED_MEDIA_HELP_EXIT_ALLOWANCE')
        if media_volume and allowed_codes != (0,):
            raise Blocked('UNREVIEWED_VOLUME_EXIT_ALLOWANCE')
        if audio_command and allowed_codes != ((255,) if audio_help else (0,)):
            raise Blocked('UNREVIEWED_AUDIO_EXIT_ALLOWANCE')
        if 255 in allowed_codes and (not (media_help or audio_help) or allowed_codes != (255,)):
            raise Blocked('UNREVIEWED_NONZERO_EXIT_ALLOWANCE')
        if args == ['shell', 'cmd', 'audio', 'set-volume', '3', '0']:
            self.result['volume_write_attempts'] += 1
        result = self.command([self.adb, '-P', PORT, '-s', SERIAL, *args], timeout=seconds)
        metadata = safe_command_metadata(args, result)
        if metadata['command_label'] in PRECONDITION_COMMAND_LABELS:
            trace = self.result.setdefault('command_diagnostics', [])
            trace.append(metadata)
            if len(trace) > COMMAND_DIAGNOSTIC_LIMIT:
                del trace[0]
                self.result['command_diagnostics_dropped'] = self.result.get('command_diagnostics_dropped', 0) + 1
        if result.returncode not in allowed_codes or result.stderr.strip() or ((media_help or media_volume or audio_command) and result.stderr != ''):
            self.result.setdefault('command_failure', safe_command_failure(args, result, allowed_codes))
            raise Blocked('SCOPED_ADB_COMMAND_FAILED')
        stdout = result.stdout.replace('\r', '')
        if media_help:
            if result.returncode != 255:
                raise Blocked('MEDIA_HELP_EXIT_CONTRACT')
            contract.verify_volume_help(stdout)
        elif args == ['shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get']:
            contract.parse_volume(stdout)
        elif args == ['shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', '0']:
            contract.verify_volume_set_zero(stdout)
        elif audio_help:
            contract.verify_audio_help(stdout)
        elif audio_command and args[3] == 'set-volume':
            contract.verify_audio_set_zero(stdout)
        elif audio_command:
            contract.parse_audio_volume(args[3], stdout)
        return stdout.strip()

    def shell(self, args, **kwargs):
        return self.adb_command(['shell', *args], **kwargs)

    def execute_audio_diagnostic(self):
        contract.require_audio_diagnostic_review(boot.PROBE_PARENT)
        if self.audio_diagnostic_index is not None:
            raise Blocked('AUDIO_DIAGNOSTIC_ALREADY_STARTED')
        super().execute()
        self.deadline = min(self.deadline, time.monotonic() + 35)
        self.audio_diagnostic_index = 0
        self.result.update(status='blocked', runtime_pass=False, diagnostic_mode='diagnose-volume',
                           volume_write_attempts=0, app_installed=False, capture_started=False,
                           permissions_granted=False, volume_before={'audio': {}}, volume_after={})
        self.stage = 'audio-diagnostic-help'
        self.shell(['cmd', 'audio'], allowed_codes=(255,))
        before = self.result['volume_before']['audio']
        for command, field in (('get-min-volume', 'min'), ('get-max-volume', 'max'), ('get-stream-volume', 'value')):
            self.stage = 'audio-diagnostic-before-' + field
            before[field] = contract.parse_audio_volume(command, self.shell(['cmd', 'audio', command, '3']))
        self.stage = 'audio-diagnostic-before-media'
        media = contract.parse_volume_state(self.shell(['cmd', 'media_session', 'volume', '--stream', '3', '--get']))
        self.result['volume_before']['media_session'] = media
        if (before['min'] != 0 or before['max'] < 1 or not 0 <= before['value'] <= before['max'] or
                (before['min'], before['max']) != (media['min'], media['max'])):
            raise Blocked('AUDIO_DIAGNOSTIC_RANGE_UNKNOWN')
        self.stage = 'audio-diagnostic-single-zero'
        self.shell(['cmd', 'audio', 'set-volume', '3', '0'])
        self.stage = 'audio-diagnostic-after-audio'
        value = contract.parse_audio_volume('get-stream-volume', self.shell(['cmd', 'audio', 'get-stream-volume', '3']))
        self.result['volume_after']['audio'] = {'value': value, 'min': before['min'], 'max': before['max']}
        self.stage = 'audio-diagnostic-after-media'
        media = contract.parse_volume_state(self.shell(['cmd', 'media_session', 'volume', '--stream', '3', '--get']))
        self.result['volume_after']['media_session'] = media
        if (media['min'], media['max']) != (before['min'], before['max']):
            raise Blocked('AUDIO_DIAGNOSTIC_RANGE_CHANGED')
        if value != 0 or media['value'] != 0:
            raise Blocked('MEDIA_VOLUME_NOT_ZERO')
        # Zero here never authorizes app installation, permission UI or capture.
        self.stage = 'audio-diagnostic-stop'
        self.result['status'] = 'audio-diagnostic-pending-host-cleanup'

    def assert_idle(self):
        previous_stage = self.stage
        self.stage = 'idle-volume-readback'
        if contract.parse_volume(self.shell(['cmd', 'media_session', 'volume', '--stream', '3', '--get'])) != 0:
            raise Blocked('MEDIA_VOLUME_NOT_ZERO')
        self.stage = 'idle-projection-readback'
        contract.verify_projection_idle(self.shell(['dumpsys', 'media_projection']))
        self.stage = 'idle-audio-readback'
        audio = self.shell(['dumpsys', 'audio'])
        self.stage = 'idle-audio-policy-readback'
        policy = self.shell(['dumpsys', 'media.audio_policy'])
        self.stage = 'idle-media-session-readback'
        sessions = self.shell(['dumpsys', 'media_session'])
        self.stage = 'idle-audio-policy-session-validation'
        contract.verify_audio_idle(audio, policy, sessions)
        self.stage = previous_stage

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
        self.stage = 'guest-precondition-locale'
        locale = self.shell(['getprop', 'persist.sys.locale'])
        if not locale:
            self.stage = 'guest-precondition-fallback-locale'
            locale = self.shell(['getprop', 'ro.product.locale'])
        if locale != 'en-US':
            raise Blocked('UNREVIEWED_LOCALE_OR_DISPLAY')
        self.stage = 'guest-precondition-display'
        if self.shell(['wm', 'size']) != 'Physical size: 480x800':
            raise Blocked('UNREVIEWED_LOCALE_OR_DISPLAY')
        self.stage = 'guest-precondition-package-absence'
        if self.shell(['pm', 'list', 'packages', PACKAGE]):
            raise Blocked('PROBE_ALREADY_INSTALLED_NOT_FRESH')
        self.stage = 'guest-precondition-volume-help'
        contract.verify_volume_help(self.shell(['cmd', 'media_session'], allowed_codes=(255,)))
        self.stage = 'guest-precondition-volume-read'
        contract.parse_volume(self.shell(['cmd', 'media_session', 'volume', '--stream', '3', '--get']))
        # The only system-volume mutation in this program is a decrease/set to zero.
        self.stage = 'guest-precondition-volume-zero'
        contract.verify_volume_set_zero(self.shell(['cmd', 'media_session', 'volume', '--stream', '3', '--set', '0']))
        self.stage = 'guest-precondition-idle'
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
        try:
            ok = super().cleanup()
        finally:
            # The inherited helper retains exception strings internally. Never
            # publish those paths/messages, including if cleanup itself raises.
            self.result['cleanup_errors'] = ['HOST_CLEANUP_UNVERIFIED'] if self.result.get('cleanup_errors') else []
        self.result['cleanup_errors'].extend(errors)
        if errors or not ok:
            self.result['status'] = 'cleanup-failed'
        elif self.result['status'] == 'audio-diagnostic-pending-host-cleanup':
            self.result.update(status='AUDIO_DIAGNOSTIC_STOPPED', runtime_pass=False)
        elif self.result['status'] == 'discovery-stopped-pending-host-cleanup':
            self.result.update(status='DISCOVERY_STOPPED', runtime_pass=False, discovery_stopped_verified=True)
        elif self.result['status'] == 'capture-verified-pending-host-cleanup':
            self.result.update(status='PASS', runtime_pass=True)
        return ok and not errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--preflight-only', action='store_true')
    parser.add_argument('--mode', choices=('capture', 'diagnose-volume', *contract.DISCOVERY_MODES), default='capture')
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
            elif args.mode == 'diagnose-volume':
                run.execute_audio_diagnostic()
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
        if args.mode == 'diagnose-volume':
            return 0 if run.result['status'] == 'AUDIO_DIAGNOSTIC_STOPPED' else 1
        if args.mode in contract.DISCOVERY_MODES:
            return 0 if run.result.get('discovery_stopped_verified') and run.result['status'] == 'DISCOVERY_STOPPED' else 1
        return 0 if run.result.get('runtime_pass') and run.result['status'] == 'PASS' else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (Blocked, artifact_gate.ArtifactGateError) as error:
        print(json.dumps({'status': 'blocked', 'reason': str(error).split(':')[0][:100], 'runtime_pass': False}))
        raise SystemExit(1)
