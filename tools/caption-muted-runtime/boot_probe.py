#!/usr/bin/env python3
"""Retargeted bounded Caption API36 boot adapter; not a standalone entry point."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlparse

# Execution and checked-out runtime source are deliberately different repos.
EXECUTION_REPOSITORY = 'xMilo-At-Your-Side/Caption-Action-build'
RUNTIME_SOURCE_REPOSITORY = 'Hatsunama/Caption-Action'
BRANCH = 'refs/heads/main'
SOURCE_BASE = '6cd8e58386d200507279ed12676ad6856d047bef'
SOURCE_HELPER_BLOB = '4498aa9375d6c998076bc0f65946f7ae10ccdc52'
SOURCE_TEST_BLOB = '52107bd567ff66f9972d8634133d2b4b7474460c'
PROBE_PARENT = '4062451c315265c827161f7e3ac925ff8df39121'
CELLS = {
    'api36': ('36', 'google_apis', '7', '4096'),
}
PACKAGES = {}
IMAGE = ''
EXPECTED_SDK = ''
EXPECTED_PAGE = ''
TOTAL_SECONDS = 20 * 60
BOOT_SECONDS = 10 * 60
LOG_LIMIT = 2 * 1024 * 1024


def select_cell(name):
    global PACKAGES, IMAGE, EXPECTED_SDK, EXPECTED_PAGE
    if name not in CELLS:
        raise Blocked('UNKNOWN_CELL')
    sdk, tag, revision, page = CELLS[name]
    IMAGE = f'system-images;android-{sdk};{tag};x86_64'
    PACKAGES = {'emulator': '37.2.12', 'platform-tools': '37.0.1', IMAGE: revision}
    EXPECTED_SDK, EXPECTED_PAGE = sdk, page


class Blocked(RuntimeError):
    pass


def policy(env):
    checks = [('public-repository', env.get('PROBE_REPOSITORY_PRIVATE') == 'false'
               and env.get('PROBE_REPOSITORY_VISIBILITY') == 'public'),
              ('github-hosted-runner', env.get('GITHUB_ACTIONS') == 'true'
               and env.get('RUNNER_ENVIRONMENT') == 'github-hosted'),
              ('repository-identity', env.get('GITHUB_REPOSITORY') == EXECUTION_REPOSITORY),
              ('runtime-source-repository', env.get('PROBE_SOURCE_REPOSITORY') == RUNTIME_SOURCE_REPOSITORY),
              ('exact-trigger', env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
               and env.get('GITHUB_REF') == BRANCH),
              ('ubuntu-24.04-image', env.get('ImageOS') == 'ubuntu24'
               and env.get('RUNNER_OS') == 'Linux'
               and env.get('RUNNER_ARCH') == 'X64'),
              ('commit-identity', bool(re.fullmatch(r'[0-9a-f]{40}', env.get('PROBE_COMMIT', '')))),
              ('execution-commit-identity', bool(re.fullmatch(r'[0-9a-f]{40}', env.get('PROBE_EXECUTION_COMMIT', '')))
               and env.get('PROBE_EXECUTION_COMMIT') == env.get('GITHUB_SHA')
               and env.get('PROBE_COMMIT') != env.get('GITHUB_SHA')),
              ('reviewed-execution-commit', bool(re.fullmatch(r'[0-9a-f]{40}', env.get('PROBE_APPROVED_EXECUTION_COMMIT', '')))
               and env.get('PROBE_APPROVED_EXECUTION_COMMIT') == env.get('GITHUB_SHA'))]
    for name, ok in checks:
        if not ok:
            raise Blocked('POLICY_BLOCKER:' + name)


def verify_ubuntu_package_uris(text, mirror_text=None):
    urls = re.findall(r"^'([^']+)'", text, re.M)
    if not urls:
        raise Blocked('APT_DOWNLOAD_PLAN_MISSING')
    hosts = ('archive.ubuntu.com', 'security.ubuntu.com', 'azure.archive.ubuntu.com')
    mirror_prefix = '/etc/apt/apt-mirrors.txt/'
    for url in urls:
        parsed = urlparse(url)
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise Blocked('NON_OFFICIAL_UBUNTU_PACKAGE_SOURCE')
        if parsed.scheme == 'mirror+file':
            if parsed.netloc or not parsed.path.startswith(mirror_prefix + 'pool/') or not parsed.path.endswith('.deb'):
                raise Blocked('UNEXPECTED_APT_MIRROR_FILE')
            if mirror_text is None:
                path = Path('/etc/apt/apt-mirrors.txt')
                info = path.lstat()
                if not stat.S_ISREG(info.st_mode) or info.st_uid != 0:
                    raise Blocked('UNTRUSTED_APT_MIRROR_FILE')
                mirror_text = path.read_text()
            mirrors = [line.split('#', 1)[0].strip().split()[0] for line in mirror_text.splitlines()
                       if line.split('#', 1)[0].strip()]
            if not mirrors:
                raise Blocked('APT_MIRROR_LIST_EMPTY')
            for mirror in mirrors:
                origin = urlparse(mirror)
                if (origin.scheme not in ('http', 'https') or origin.hostname not in hosts or
                        origin.path.rstrip('/') != '/ubuntu' or origin.username or origin.password or
                        origin.query or origin.fragment):
                    raise Blocked('NON_OFFICIAL_UBUNTU_MIRROR')
        elif (parsed.scheme not in ('http', 'https') or parsed.hostname not in hosts or
              not parsed.path.startswith('/ubuntu/pool/') or not parsed.path.endswith('.deb')):
            raise Blocked('NON_OFFICIAL_UBUNTU_PACKAGE_SOURCE')
    return len(urls)


def adb_server_args(adb):
    # ADB's listener parser accepts literal localhost, not numeric 127.0.0.1.
    return [adb, '-L', 'tcp:localhost:5038', 'server', 'nodaemon']


def verify_loopback_listener(ipv4, ipv6):
    found = []
    for family, table in ((4, ipv4), (6, ipv6)):
        for row in table.splitlines()[1:]:
            fields = row.split()
            if len(fields) >= 4 and fields[3] == '0A':
                address, port = fields[1].split(':')
                if int(port, 16) == 5038:
                    found.append((family, address))
    if found != [(4, '0100007F')]:
        raise Blocked('ADB_LISTENER_NOT_EXCLUSIVELY_IPV4_LOOPBACK')


class KvmLease:
    """Only this runner UID, only the known disposable /dev/kvm device."""
    def __init__(self):
        self.changed = False
        self.uid = os.getuid()
        self.before = None

    def command(self, args):
        result = subprocess.run(args, stdin=subprocess.DEVNULL, capture_output=True,
                                text=True, timeout=15, check=True)
        return result.stdout.strip()

    def acquire(self):
        p = Path('/dev/kvm')
        info = p.lstat()
        if (not stat.S_ISCHR(info.st_mode) or info.st_uid != 0 or
                stat.S_IMODE(info.st_mode) != 0o660 or self.uid == 0 or
                (os.major(info.st_rdev), os.minor(info.st_rdev)) != (10, 232)):
            raise Blocked('KVM_DEVICE_IDENTITY_OR_MODE')
        self.before = self.command(['getfacl', '-cp', '/dev/kvm'])
        if self.before != 'user::rw-\ngroup::rw-\nother::---':
            raise Blocked('KVM_UNEXPECTED_EXISTING_ACL')
        if not os.access(p, os.R_OK | os.W_OK):
            # Mark before the call so cleanup also handles an uncertain interrupted grant.
            self.changed = True
            self.command(['sudo', '-n', 'setfacl', '-m', f'u:{self.uid}:rw', '/dev/kvm'])
        if not os.access(p, os.R_OK | os.W_OK):
            raise Blocked('KVM_ACCESS_NOT_GRANTED')
        return {'uid': self.uid, 'mode': '0660', 'device': '10:232',
                'runner_only_acl_added': self.changed, 'read_write': True}

    def release(self):
        if self.changed:
            self.command(['sudo', '-n', 'setfacl', '--set',
                          'u::rw-,g::rw-,o::---', '/dev/kvm'])
            if self.command(['getfacl', '-cp', '/dev/kvm']) != self.before:
                raise Blocked('KVM_ACL_RESTORE_FAILED')
            self.changed = False


def parse_available(text):
    found = {}
    in_available = False
    for line in text.splitlines():
        if line.strip() == 'Available Packages:':
            in_available = True
            continue
        if line.strip().endswith(':'):
            in_available = False
        if in_available and '|' in line:
            fields = [s.strip() for s in line.split('|')]
            if fields[0] in PACKAGES:
                if fields[0] in found:
                    raise Blocked('AMBIGUOUS_PACKAGE:' + fields[0])
                found[fields[0]] = fields[1]
    if found != PACKAGES:
        raise Blocked('PACKAGE_REVISION_DRIFT:' + json.dumps(found, sort_keys=True))
    return found


def properties(path):
    return dict(line.strip().split('=', 1) for line in path.read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))


def validate_runtime(facts):
    required = {'boot_completed': '1', 'page_size': EXPECTED_PAGE, 'sdk': EXPECTED_SDK, 'abi': 'x86_64'}
    for k, v in required.items():
        if facts.get(k) != v:
            raise Blocked('RUNTIME_MISMATCH:' + k)
    if not facts.get('fingerprint') or 'x86_64' not in facts.get('abilist', '').split(','):
        raise Blocked('RUNTIME_FACTS_INCOMPLETE')


class Probe:
    def __init__(self, evidence, temp_root):
        self.evidence = evidence
        self.temp_root = temp_root
        self.deadline = time.monotonic() + TOTAL_SECONDS
        self.children = []
        self.files = []
        self.stage = 'preflight'
        self.work = None
        self.env = None
        self.kvm = KvmLease()
        self.result = {'status': 'blocked', 'scope': 'environment-only', 'page_size_verified': False}

    def write(self, name, value):
        (self.evidence / name).write_text(value)

    def remaining(self, requested):
        left = self.deadline - time.monotonic()
        if left <= 0:
            raise Blocked('TOTAL_DEADLINE_EXCEEDED')
        return min(requested, left)

    def spawn(self, args, name):
        log = self.evidence / name
        f = log.open('wb')
        self.files.append(f)
        p = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=f, stderr=subprocess.STDOUT,
                             env=self.env, start_new_session=True)
        self.children.append(p)
        return p, log

    def stop(self, p):
        # Signal only process groups created and owned by this probe; no global pkill.
        try:
            os.killpg(p.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            raise Blocked('OWNED_PROCESS_CLEANUP_FAILED')
        if p in self.children:
            self.children.remove(p)

    def run(self, args, name, seconds=30, allow_failure=False):
        end = time.monotonic() + self.remaining(seconds)
        p, log = self.spawn(args, name)
        while p.poll() is None:
            now = time.monotonic()
            if log.stat().st_size > LOG_LIMIT:
                self.stop(p)
                raise Blocked('COMMAND_LOG_LIMIT:' + name)
            if now >= self.deadline:
                self.stop(p)
                raise Blocked('TOTAL_DEADLINE_EXCEEDED')
            if now >= end:
                self.stop(p)
                if allow_failure:
                    with (self.evidence / 'commands.jsonl').open('a') as f:
                        f.write(json.dumps({'stage': self.stage, 'log': name,
                                            'result': 'transient-command-timeout'}) + '\n')
                    return ''
                raise Blocked('COMMAND_TIMEOUT:' + name)
            time.sleep(.2)
        code = p.returncode
        with (self.evidence / 'commands.jsonl').open('a') as f:
            f.write(json.dumps({'stage': self.stage, 'log': name, 'exit': code}) + '\n')
        self.stop(p)  # Also reap any child left in its owned process group.
        text = log.read_text(errors='replace')[:LOG_LIMIT]
        if code and not allow_failure:
            if re.search(r'license.*(not accepted|accept|review)|Accept\?.*y/N', text, re.I):
                raise Blocked('LICENSE_BLOCKER:' + name + ': no acceptance was supplied')
            missing = re.search(r'error while loading shared libraries:\s*([A-Za-z0-9_+.-]+\.so(?:\.[A-Za-z0-9_.-]+)*):', text)
            if missing:
                raise Blocked('MISSING_HOST_LIBRARY:' + missing[1])
            raise Blocked('COMMAND_FAILED:' + name + ':exit=' + str(code))
        return '' if code else text.strip()

    def resources(self, minimum_disk_gib, minimum_mem_gib):
        free = shutil.disk_usage(self.temp_root).free
        mem = int(re.search(r'^MemAvailable:\s+(\d+)', Path('/proc/meminfo').read_text(), re.M)[1]) * 1024
        cpus = os.cpu_count() or 0
        with (self.evidence / 'resources.jsonl').open('a') as f:
            f.write(json.dumps({'stage': self.stage, 'free_disk_bytes': free,
                                'available_memory_bytes': mem, 'logical_cpus': cpus}) + '\n')
        if free < minimum_disk_gib * 2**30 or mem < minimum_mem_gib * 2**30 or cpus < 2:
            raise Blocked('RESOURCE_BLOCKER:' + self.stage)

    def setup(self):
        original = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME', ''))
        cmd = original / 'cmdline-tools/latest'
        if not (cmd / 'bin/sdkmanager').is_file() or not (cmd / 'bin/avdmanager').is_file():
            raise Blocked('PREINSTALLED_COMMAND_LINE_TOOLS_MISSING')
        licenses = original / 'licenses'
        accepted = sorted(p for p in licenses.glob('*') if p.is_file() and not p.is_symlink())
        if not accepted:
            raise Blocked('LICENSE_BLOCKER:no preaccepted runner license markers')
        self.write('runner-license-marker-hashes.json', json.dumps({
            p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in accepted}, indent=2))
        inventory = {}
        for name in ['cmdline-tools;latest', *PACKAGES]:
            p = original.joinpath(*name.split(';')) / 'source.properties'
            inventory[name] = properties(p).get('Pkg.Revision') if p.is_file() else 'not-installed'
        self.write('preinstalled-revisions.json', json.dumps(inventory, indent=2))
        self.resources(12, 6)
        # Before any tool execution, package download, adb server or emulator launch.
        self.write('kvm-access.json', json.dumps(self.kvm.acquire(), indent=2))
        self.work = Path(tempfile.mkdtemp(prefix='caption-muted-', dir=self.temp_root))
        sdk = self.work / 'sdk'
        home = self.work / 'home'
        android = home / '.android'
        for p in (sdk / 'licenses', android / 'avd', self.work / 'tmp'):
            p.mkdir(parents=True, exist_ok=True)
        shutil.copytree(cmd, sdk / 'cmdline-tools/latest')
        for p in accepted:
            shutil.copy2(p, sdk / 'licenses' / p.name)
        # Allowlist child environment: never inherit access tokens, signing data,
        # existing user home/ADB key paths, Android custom repositories or proxies.
        self.env = {k: os.environ[k] for k in ('PATH', 'JAVA_HOME', 'LANG') if k in os.environ}
        self.env.update(HOME=str(home), ANDROID_USER_HOME=str(android),
                        ANDROID_EMULATOR_HOME=str(android), ANDROID_AVD_HOME=str(android / 'avd'),
                        ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
                        TMPDIR=str(self.work / 'tmp'),
                        ADB_SERVER_SOCKET='tcp:localhost:5038', ANDROID_ADB_SERVER_PORT='5038',
                        ADB_MDNS_AUTO_CONNECT='0', ADB_USB='0', ADB_MDNS='0')
        self.sdk = sdk
        self.sdkmanager = str(sdk / 'cmdline-tools/latest/bin/sdkmanager')
        self.avdmanager = str(sdk / 'cmdline-tools/latest/bin/avdmanager')
        self.write('command-line-tools-properties.txt', (cmd / 'source.properties').read_text())

    def execute(self):
        self.setup()
        self.stage = 'package-metadata'
        listing = self.run([self.sdkmanager, '--list', '--channel=0', '--sdk_root=' + str(self.sdk)],
                           'available-packages.txt', seconds=90)
        self.write('expected-packages.json', json.dumps(parse_available(listing), indent=2))
        self.stage = 'package-installation'
        self.run([self.sdkmanager, '--channel=0', '--sdk_root=' + str(self.sdk), *PACKAGES],
                 'sdk-install.txt', seconds=360)
        # Detect missing packages and metadata races rather than silently accepting newer revisions.
        installed = {}
        for name, revision in PACKAGES.items():
            path = self.sdk.joinpath(*name.split(';')) / 'source.properties'
            if not path.is_file():
                raise Blocked('PACKAGE_NOT_INSTALLED_OR_LICENSE_BLOCKER:' + name)
            props = properties(path)
            installed[name] = props.get('Pkg.Revision')
            if installed[name] != revision:
                raise Blocked('INSTALLED_REVISION_MISMATCH:' + name)
        self.write('installed-packages.json', json.dumps(installed, indent=2))
        after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in (self.sdk / 'licenses').glob('*') if p.is_file()}
        before = json.loads((self.evidence / 'runner-license-marker-hashes.json').read_text())
        if after != before:
            raise Blocked('LICENSE_MARKERS_CHANGED')
        self.stage = 'cli-verification'
        emulator = str(self.sdk / 'emulator/emulator')
        adb = str(self.sdk / 'platform-tools/adb')
        self.run([emulator, '-version'], 'emulator-version.txt')
        self.run([adb, 'version'], 'adb-version.txt')
        accel = self.run([emulator, '-help-accel'], 'emulator-help-accel.txt')
        gpu = self.run([emulator, '-help-gpu'], 'emulator-help-gpu.txt')
        helptext = self.run([emulator, '-help-all'], 'emulator-help-all.txt')
        if not re.search(r'\bon\b', accel) or not re.search(r'\bsoftware\b', gpu):
            raise Blocked('CLI_MODES_NOT_ADVERTISED')
        for option in ('no-window', 'no-audio', 'no-snapshot', 'camera-back', 'camera-front', 'port'):
            if option not in helptext:
                raise Blocked('CLI_OPTION_NOT_ADVERTISED:' + option)
        self.run([emulator, '-accel-check'], 'accel-check.txt')
        self.result['acceleration_check_passed'] = True
        self.stage = 'avd-creation'
        self.run([self.avdmanager, 'create', 'avd', '--name', 'caption_muted_probe', '--package', IMAGE,
                  '--device', 'pixel_2', '--path', str(self.work / 'avd')], 'avd-create.txt', seconds=30)
        config = self.work / 'avd/config.ini'
        with config.open('a') as f:
            f.write('\nhw.ramSize=2048\nhw.cpu.ncore=2\nhw.lcd.width=480\nhw.lcd.height=800\n'
                    'hw.lcd.density=160\ndisk.dataPartition.size=2G\nhw.sdCard=no\n'
                    'hw.camera.back=none\nhw.camera.front=none\nhw.audioInput=no\n')
        self.write('avd-config.txt', config.read_text())
        self.stage = 'prelaunch-resources'
        self.resources(4, 5)
        self.stage = 'adb-server'
        # Foreground server on a probe-only port so cleanup owns its process group.
        server, serverlog = self.spawn(adb_server_args(adb), 'adb-server.txt')
        time.sleep(1)
        if server.poll() is not None:
            raise Blocked('ADB_SERVER_EXITED')
        verify_loopback_listener(Path('/proc/net/tcp').read_text(), Path('/proc/net/tcp6').read_text())
        self.result['adb_loopback_verified'] = True
        self.stage = 'emulator-boot'
        args = [emulator, '-avd', 'caption_muted_probe', '-accel', 'on', '-gpu', 'software',
                '-no-window', '-no-audio', '-no-snapshot', '-no-boot-anim',
                '-camera-back', 'none', '-camera-front', 'none', '-port', '5556',
                '-memory', '2048', '-cores', '2']
        self.write('emulator-argv.json', json.dumps(args, indent=2))
        if hasattr(self, 'before_guest_launch'): self.before_guest_launch()
        emu, emulog = self.spawn(args, 'emulator.txt')
        self.emulator, self.server, self.adb = emu, server, adb
        end = time.monotonic() + self.remaining(BOOT_SECONDS)
        def shell(command, filename):
            if hasattr(self, 'verify_guest_transport') and not self.verify_guest_transport():
                raise Blocked('OWN_GUEST_TRANSPORT_NOT_READY')
            return self.run([adb, '-P', '5038', '-s', 'emulator-5556', 'shell', *command],
                            filename, seconds=8, allow_failure=True).replace('\r', '').strip()
        while time.monotonic() < end:
            if emu.poll() is not None:
                raise Blocked('EMULATOR_EXITED:exit=' + str(emu.returncode))
            if server.poll() is not None:
                raise Blocked('ADB_SERVER_EXITED_DURING_BOOT')
            if emulog.stat().st_size > LOG_LIMIT or serverlog.stat().st_size > LOG_LIMIT:
                raise Blocked('EMULATOR_OR_ADB_LOG_LIMIT')
            self.resources(2, 1)
            if hasattr(self, 'verify_guest_transport') and not self.verify_guest_transport():
                time.sleep(.2)
                continue
            boot = shell(['getprop', 'sys.boot_completed'], 'boot-last.txt')
            if boot == '1':
                self.stage = 'runtime-facts'
                facts = {'boot_completed': boot,
                         'page_size': shell(['getconf', 'PAGE_SIZE'], 'page-size.txt')}
                for key, prop in {'sdk': 'ro.build.version.sdk', 'abi': 'ro.product.cpu.abi',
                                  'abilist': 'ro.product.cpu.abilist',
                                  'fingerprint': 'ro.build.fingerprint'}.items():
                    facts[key] = shell(['getprop', prop], key + '.txt')
                facts['page_size_source'] = 'getconf PAGE_SIZE'
                self.write('runtime-facts.json', json.dumps(facts, indent=2))
                validate_runtime(facts)
                self.result.update(status='environment-boot-verified', page_size_verified=True)
                return
            time.sleep(min(10, max(0, end - time.monotonic())))
        raise Blocked('BOOT_TIMEOUT: no verified Android boot within bounded exploratory window')

    def cleanup(self):
        errors = []
        for p in list(reversed(self.children)):
            try:
                self.stop(p)
            except Exception as e:
                errors.append(str(e))
        try:
            self.kvm.release()
        except Exception as e:
            errors.append('KVM cleanup: ' + str(e))
        for f in self.files:
            f.close()
        # Persist only bounded allowlisted files from this newly-created evidence dir.
        for p in self.evidence.iterdir():
            if p.is_file() and p.stat().st_size > LOG_LIMIT:
                with p.open('r+b') as f:
                    f.truncate(LOG_LIMIT)
                self.result.setdefault('truncated_logs', []).append(p.name)
        if self.work:
            shutil.rmtree(self.work)
        self.result['cleanup_errors'] = errors
        if errors:
            self.result['status'] = 'cleanup-failed'
        return not errors


if __name__ == '__main__':
    raise SystemExit('BLOCKED: use reviewed capture_runtime.py entry point; direct boot is disabled')
