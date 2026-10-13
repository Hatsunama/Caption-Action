"""Independent deadline process; never imports or waits on the main log/UI parser."""
import argparse
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import tempfile
import time
from boot_probe import Blocked
from runtime_contract import PACKAGE, PORT, SERIAL, verify_package

WATCHDOG_SECONDS = 35.0


def run_bounded(argv, *, timeout, max_output_bytes, env=None):
    """Owned command group, bounded wall time and file-backed output (no pipe deadlock)."""
    if timeout <= 0 or timeout > 60 or not 0 < max_output_bytes <= 2 * 1024 * 1024:
        raise Blocked('INVALID_COMMAND_BOUND')
    safe_env = env if env is not None else {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG') if k in os.environ}
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as error:
        child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=output, stderr=error,
                                 env=safe_env, start_new_session=True)
        deadline = time.monotonic() + timeout
        try:
            while child.poll() is None:
                if os.fstat(output.fileno()).st_size + os.fstat(error.fileno()).st_size > max_output_bytes:
                    raise Blocked('BOUNDED_COMMAND_OUTPUT_LIMIT')
                if time.monotonic() >= deadline:
                    raise Blocked('BOUNDED_COMMAND_TIMEOUT')
                time.sleep(.03)
            if os.fstat(output.fileno()).st_size + os.fstat(error.fileno()).st_size > max_output_bytes:
                raise Blocked('BOUNDED_COMMAND_OUTPUT_LIMIT')
            output.seek(0); error.seek(0)
            return subprocess.CompletedProcess(argv, child.returncode, output.read().decode('utf-8', 'strict'),
                                               error.read().decode('utf-8', 'strict'))
        finally:
            # Even a completed leader may have left descendants in its own group.
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(child.pid, sig)
                except ProcessLookupError:
                    pass
                try:
                    child.wait(timeout=.5)
                except subprocess.TimeoutExpired:
                    pass
            if child.poll() is None:
                raise Blocked('BOUNDED_COMMAND_GROUP_CLEANUP_FAILED')


def process_identity(pid):
    stat = Path(f'/proc/{pid}/stat').read_text()
    # Linux stat fields after comm; comm can contain spaces and parentheses.
    parts = stat[stat.rindex(')') + 2:].split()
    return {'pid': pid, 'uid': Path(f'/proc/{pid}').stat().st_uid,
            'starttime': int(parts[19]), 'pgrp': int(parts[2])}


def owned_emulator_identity(pid):
    identity = process_identity(pid)
    if identity['uid'] != os.getuid() or identity['pgrp'] != pid or pid in (os.getpid(), os.getppid()):
        raise Blocked('UNOWNED_EMULATOR_PROCESS_GROUP')
    return identity


def destroy_owned_guest(identity):
    """Kill only the recorded owned session, including children after leader exit."""
    try:
        current = owned_emulator_identity(identity['pid'])
        if current != identity:
            raise Blocked('EMULATOR_PROCESS_IDENTITY_CHANGED')
    except FileNotFoundError:
        pass
    def members():
        live = []
        for entry in Path('/proc').iterdir():
            if not entry.name.isdigit():
                continue
            try:
                raw = (entry / 'stat').read_text()
                fields = raw[raw.rindex(')') + 2:].split()
                if int(fields[2]) != identity['pid']:
                    continue
                if (entry.stat().st_uid != identity['uid'] or int(fields[3]) != identity['pid'] or
                        int(fields[19]) < identity['starttime']):
                    raise Blocked('EMULATOR_GROUP_IDENTITY_CHANGED')
                if fields[0] != 'Z':
                    live.append(int(entry.name))
            except (FileNotFoundError, ProcessLookupError):
                pass
        return live
    # Always send SIGKILL after TERM; a zombie leader does not prove its children stopped.
    for sig in (signal.SIGTERM, signal.SIGKILL):
        members()
        try:
            os.killpg(identity['pid'], sig)
        except ProcessLookupError:
            return True
        time.sleep(.15)
    end = time.monotonic() + 1
    while time.monotonic() < end:
        if not members():
            return True
        time.sleep(.03)
    raise Blocked('EMULATOR_GROUP_STILL_ALIVE')


def force_stop_verified(adb, runner=run_bounded):
    base = [adb, '-P', PORT, '-s', SERIAL, 'shell']
    stop = runner(base + ['am', 'force-stop', '--user', '0', PACKAGE], timeout=2, max_output_bytes=32768)
    if stop.returncode or stop.stdout.strip() or stop.stderr.strip():
        raise Blocked('FORCE_STOP_FAILED')
    pid = runner(base + ['pidof', PACKAGE], timeout=2, max_output_bytes=32768)
    if pid.returncode != 1 or pid.stdout.strip() or pid.stderr.strip():
        raise Blocked('OWN_PROCESS_NOT_STOPPED')
    package = runner(base + ['dumpsys', 'package', PACKAGE], timeout=2, max_output_bytes=262144)
    if package.returncode or package.stderr.strip():
        raise Blocked('PACKAGE_STOP_READBACK_FAILED')
    verify_package(package.stdout, stopped=True)
    return True


def watchdog_environment():
    # The watchdog inherits only the parent's disposable home/SDK environment.
    # Keep it for all adb commands; never fall back to USB/mDNS defaults.
    env = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG', 'HOME',
           'ANDROID_USER_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'TMPDIR') if k in os.environ}
    env.update(ADB_SERVER_SOCKET='tcp:localhost:5038', ANDROID_ADB_SERVER_PORT='5038',
               ADB_MDNS_AUTO_CONNECT='0', ADB_USB='0', ADB_MDNS='0')
    return env


def watch(fd, deadline, adb, identity, receipt, ready, runner=None, destroy=destroy_owned_guest):
    if runner is None:
        runner = lambda argv, **kw: run_bounded(argv, env=watchdog_environment(), **kw)
    if not 0 < deadline - time.monotonic() <= WATCHDOG_SECONDS:
        raise Blocked('WATCHDOG_DEADLINE_INVALID')
    if owned_emulator_identity(identity['pid']) != identity:
        raise Blocked('WATCHDOG_EMULATOR_IDENTITY_INVALID')
    ready.write_text('armed\n')
    event = 'deadline'
    while True:
        left = deadline - time.monotonic()
        if left <= 0:
            break
        available, _, _ = select.select([fd], [], [], left)
        if not available:
            continue
        value = os.read(fd, 2)
        if time.monotonic() >= deadline:
            break
        if value == b'D':
            receipt.write_text(json.dumps({'event': 'disarmed', 'stopped': True}))
            return 0
        # Parent closed/died or sent an invalid instruction: stop immediately.
        event = 'parent_closed' if not value else 'invalid_control'
        break
    result = {'event': event, 'stopped': False, 'guest_destroyed': False}
    try:
        result['stopped'] = force_stop_verified(adb, runner)
    except Exception:
        # Independent fallback even when ADB, Android, parser, or parent is hung.
        try:
            result['guest_destroyed'] = destroy(identity)
        except Exception:
            result['escalation_failed'] = True
    receipt.write_text(json.dumps(result))
    return 2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fd', type=int, required=True)
    parser.add_argument('--deadline', type=float, required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--identity', required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--ready', type=Path, required=True)
    a = parser.parse_args()
    return watch(a.fd, a.deadline, a.adb, json.loads(a.identity), a.receipt, a.ready)


if __name__ == '__main__':
    raise SystemExit(main())
