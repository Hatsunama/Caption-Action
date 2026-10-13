"""Strict, pure parsers. Unknown or incomplete evidence is never a pass."""
import math
import hashlib
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from collections import Counter
from boot_probe import Blocked

PACKAGE = 'com.hatsunama.captionaction.captureprobe'
ACTIVITY = PACKAGE + '/.ProbeActivity'
LABEL = 'Caption Muted Capture Probe'
TAG = 'CaptionCaptureProbe'
SERIAL = 'emulator-5556'
PORT = '5038'
PHASES = ('SILENT', 'PERMITTED_TONE', 'DENIED_TONE')
CAPTURE_LIMIT = 16384
UI_LIMIT = 256 * 1024
FINAL = 'overall=PASS mediaIndex=0 ownUid=true platformOnly=true teardownVerified=true'


def parse_metrics(text, complete=True):
    if len(text.encode()) > CAPTURE_LIMIT:
        raise Blocked('CAPTURE_LOG_LIMIT')
    lines = [x for x in text.splitlines() if x and x != '--------- beginning of main']
    result = []
    final_seen = False
    pattern = (r'(SILENT|PERMITTED_TONE|DENIED_TONE) result=PASS sourceFrames=(\d+) '
               r'samples=(\d+) nonzero=(\d+) rms=(\d+\.\d{3}) peak=(\d+) '
               r'toneFraction=(\d+\.\d{4}) sha256=([0-9a-f]{64})')
    for line in lines:
        if final_seen:
            raise Blocked('CONTRADICTORY_OR_DUPLICATE_LATE_LOG')
        if line == FINAL:
            if len(result) != 3:
                raise Blocked('FINAL_BEFORE_PHASES')
            final_seen = True
            continue
        match = re.fullmatch(pattern, line)
        if not match:
            raise Blocked('UNKNOWN_FAILED_OR_NONSCALAR_PROBE_LOG')
        phase, frames, samples, nonzero, rms, peak, fraction, digest = match.groups()
        if len(result) >= 3 or phase != PHASES[len(result)]:
            raise Blocked('PHASE_ORDER_OR_DUPLICATE')
        frames, samples, nonzero, peak = map(int, (frames, samples, nonzero, peak))
        rms, fraction = float(rms), float(fraction)
        if not (64000 <= frames <= 128000 and 32000 <= samples <= 128000 and
                0 <= nonzero <= samples and 0 <= peak <= 32768 and
                math.isfinite(rms) and 0 <= rms <= peak and 0 <= fraction <= 1):
            raise Blocked('PHASE_SCALAR_BOUNDS')
        if ((phase == 'PERMITTED_TONE' and (rms < 100 or fraction < .70)) or
                (phase != 'PERMITTED_TONE' and rms >= 2)):
            raise Blocked('PHASE_THRESHOLD_INCONCLUSIVE')
        result.append(dict(phase=phase, sourceFrames=frames, samples=samples, nonzero=nonzero,
                           rms=rms, peak=peak, toneFraction=fraction, sha256=digest))
    if complete and (len(result) != 3 or not final_seen):
        raise Blocked('MISSING_PHASES_OR_VERIFIED_FINAL')
    return {'phases': result, 'final_verified': final_seen}


def exactly(pattern, text, expected=None, flags=0):
    hits = re.findall(pattern, text, flags)
    if len(hits) != 1 or (expected is not None and hits[0] != expected):
        raise Blocked('UNKNOWN_OR_AMBIGUOUS_CURRENT_STATE')
    return hits[0]


def section(text, begin, end):
    return exactly(begin + r'(.*?)' + end, text, flags=re.S)


def verify_projection_idle(text):
    if text.strip() != 'MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull':
        raise Blocked('PROJECTION_PRESENT_OR_UNKNOWN')


def verify_audio_idle(audio, policy, sessions):
    """AOSP Android16 current sections only. History never substitutes for state.

    This intentionally requires no registered player or recorder; a harmless idle
    system player is still inconclusive until explicitly reviewed, never ignored.
    """
    mode = section(audio, r'\nAudio mode: *\n', r'\nAudio routes:')
    expected = ('- Requested mode = MODE_NORMAL\n- Actual mode = MODE_NORMAL\n'
                '- Mode owner: \n   None\n- Mode owner stack: \n   Empty')
    if mode.strip() != expected:
        raise Blocked('AUDIO_MODE_UNKNOWN_OR_UNSAFE')
    exactly(r'^  mUseFixedVolume=(true|false)$', audio, 'false', re.M)
    routes = section(audio, r'\nAudio routes:\n', r'\nOther state:')
    if routes.strip() != 'mMainType=0x0\n  mBluetoothName=null':
        raise Blocked('EXTERNAL_AUDIO_ROUTE_OR_UNKNOWN')
    # Anchored current sections, explicitly before historical event logger output.
    players = section(audio, r'\n  players:\n', r'\n  ducked players piids:')
    if players.strip():
        raise Blocked('PLAYBACK_OBJECT_PRESENT_OR_UNKNOWN')
    recorders = section(audio, r'\nRecordActivityMonitor dump time: [^\n]+\n',
                        r'\nEvents log: recording activity received by AudioService')
    if recorders.strip():
        raise Blocked('RECORDING_OBJECT_PRESENT_OR_UNKNOWN')
    count, outputs = exactly(r'\n Available output devices \((\d+)\):\n(.*?)\n Available input devices',
                             policy, flags=re.S)
    types = re.findall(r'\{(AUDIO_DEVICE_OUT_[A-Z0-9_]+)(?:,|\})', outputs)
    if (len(types) != int(count) or not types or 'AUDIO_DEVICE_OUT_SPEAKER' not in types or
            set(types) - {'AUDIO_DEVICE_OUT_SPEAKER', 'AUDIO_DEVICE_OUT_EARPIECE'}):
        raise Blocked('BUILTIN_SPEAKER_UNAVAILABLE_OR_EXTERNAL_OUTPUT')
    if not sessions.startswith('MEDIA SESSION SERVICE (dumpsys media_session)\n'):
        raise Blocked('MEDIA_SESSION_DUMP_UNKNOWN')
    exactly(r'^Global priority session is (.*)$', sessions, 'null', re.M)
    counts = re.findall(r'^ *Sessions Stack - have (\d+) sessions:$', sessions, re.M)
    if counts != ['0']:
        raise Blocked('MEDIA_SESSION_PRESENT_OR_UNKNOWN')
    return True


MEDIA_HELP_HEADER = 'usage: media_session [subcommand] [options]'
MEDIA_HELP_OPTION_LINES = (
    'media_session volume:  the options are as follows: ',
    '\t\t--stream STREAM selects the stream to control, see AudioManager.STREAM_*',
    '\t\t                controls AudioManager.STREAM_MUSIC if no stream is specified',
    '\t\t--set INDEX     sets the volume index value',
    '\t\t--get           outputs the current volume',
)
VOLUME_CONTROL_PREFIX = '[V] will control stream=3 (STREAM_MUSIC)\n'
VOLUME_GET_PREFIX = VOLUME_CONTROL_PREFIX + '[V] will get volume\n[V] Connecting to AudioService\n'
VOLUME_SET_ZERO_LINES = VOLUME_CONTROL_PREFIX + '[V] will set volume to index=0\n[V] Connecting to AudioService'


def verify_volume_help(text):
    # Bare cmd media_session only: Android16 prints this on stdout and returns-1
    # (shell255). The caller independently requires empty stderr and that status.
    lines = text.splitlines()
    if (len(text.encode()) > 8192 or not text.startswith(MEDIA_HELP_HEADER + '\n') or
            lines.count(MEDIA_HELP_HEADER) != 1 or
            any(lines.count(line) != 1 for line in MEDIA_HELP_OPTION_LINES)):
        raise Blocked('UNSUPPORTED_VOLUME_CLI')


def parse_volume_state(text):
    # VolumeCtrl logs each parsed option in argv order, then the connection, then
    # getStreamVolume. No unrelated/missing/reordered/error stdout is accepted.
    pattern = re.escape(VOLUME_GET_PREFIX) + r'\[V\] volume is (\d{1,10}) in range \[(\d{1,10})\.\.(\d{1,10})\]\n?'
    match = re.fullmatch(pattern, text)
    if match is None:
        raise Blocked('VOLUME_READBACK_FORMAT_UNKNOWN')
    value, minimum, maximum = map(int, match.groups())
    if minimum != 0 or maximum < 1 or not 0 <= value <= maximum:
        raise Blocked('VOLUME_RANGE_UNKNOWN')
    return {'value': value, 'min': minimum, 'max': maximum}


def parse_volume(text):
    return parse_volume_state(text)['value']


# Android16 AudioManagerShellCommand.onHelp, exact source-derived stdout.
AUDIO_HELP_SHA256 = '53dccfa4e56a6efca32efcb478e07e001702531576f101dc532a2b85362300a6'
AUDIO_DIAGNOSTIC_REVIEW = {'mode': 'diagnose-volume', 'parent': '4062451c315265c827161f7e3ac925ff8df39121', 'scope': 'single-zero-no-install-ui-capture'}


def require_audio_diagnostic_review(parent):
    if AUDIO_DIAGNOSTIC_REVIEW != {'mode': 'diagnose-volume', 'parent': parent,
                                   'scope': 'single-zero-no-install-ui-capture'}:
        raise Blocked('UNREVIEWED_AUDIO_DIAGNOSTIC')


def verify_audio_help(text):
    if len(text.encode()) != 1731 or hashlib.sha256(text.encode()).hexdigest() != AUDIO_HELP_SHA256:
        raise Blocked('UNSUPPORTED_AUDIO_CLI')


def parse_audio_volume(command, text):
    methods = {'get-min-volume': 'getStreamMinVolume', 'get-max-volume': 'getStreamMaxVolume',
               'get-stream-volume': 'getStreamVolume'}
    if command not in methods:
        raise Blocked('UNREVIEWED_AUDIO_VOLUME_READ')
    match = re.fullmatch(re.escape('AudioManager.' + methods[command] + '(3) -> ') + r'([0-9]{1,10})\n?', text)
    if match is None or int(match[1]) > 2147483647:
        raise Blocked('AUDIO_VOLUME_READ_FORMAT_UNKNOWN')
    return int(match[1])


def verify_audio_set_zero(text):
    if text not in ('calling AudioManager.setStreamVolume(3, 0, 0)',
                    'calling AudioManager.setStreamVolume(3, 0, 0)\n'):
        raise Blocked('AUDIO_SET_ZERO_FORMAT_UNKNOWN')


def verify_volume_set_zero(text):
    # This proves only the exact CLI path/output. An independent subsequent get
    # must still read0; a successful setter return alone is never mute evidence.
    if re.fullmatch(re.escape(VOLUME_SET_ZERO_LINES) + r'\n?', text) is None:
        raise Blocked('VOLUME_SET_ZERO_FORMAT_UNKNOWN')


def verify_package(text, *, stopped=None, permission=None):
    # Full single-package dump only. User0 is the only approved disposable user.
    exactly(r'^  Package \[([^\]]+)\] \([^\n]+\):$', text, PACKAGE, re.M)
    uid = exactly(r'^    userId=(\d+)$', text, flags=re.M)
    if not 10000 <= int(uid) < 20000:
        raise Blocked('PACKAGE_UID_OUTSIDE_USER0_APP_RANGE')
    user_lines = re.findall(r'^    User (\d+): ([^\n]+)$', text, re.M)
    if len(user_lines) != 1 or user_lines[0][0] != '0':
        raise Blocked('PACKAGE_USER_UNKNOWN')
    state = exactly(r'(?:^| )stopped=(true|false)(?: |$)', user_lines[0][1])
    if stopped is not None and state != str(stopped).lower():
        raise Blocked('PACKAGE_STOPPED_STATE_UNVERIFIED')
    if permission is not None:
        runtime = section(text, r'      runtime permissions:\n', r'(?:\n\n|\Z)')
        grant = exactly(r'^        android.permission.RECORD_AUDIO: granted=(true|false), flags=\[.*\]$',
                        runtime, flags=re.M)
        if grant != str(permission).lower():
            raise Blocked('RECORD_AUDIO_GRANT_UNVERIFIED')
    return int(uid)


def verify_service_stopped(text):
    if not re.fullmatch(r'ACTIVITY MANAGER SERVICES \(dumpsys activity services\)\n  \(nothing\)\s*', text):
        raise Blocked('OWN_SERVICE_PRESENT_OR_UNKNOWN')


@dataclass(frozen=True)
class Selector:
    text: str
    resource: str
    class_name: str


@dataclass(frozen=True)
class UiSpec:
    owner: str
    component: str
    target: Selector
    required_texts: tuple
    allowed_clickables: tuple
    action: str


def foreground(activity, window, component):
    resumed = re.findall(r'^\s*mResumedActivity: ActivityRecord\{[^\n]+ u0 (\S+) t\d+\}?\s*$', activity, re.M)
    focused = re.findall(r'^\s*mCurrentFocus=Window\{[^\n]+ u0 (\S+)\}\s*$', window, re.M)
    if resumed != [component] or focused != [component]:
        raise Blocked('FOREGROUND_OWNER_ACTIVITY_MISMATCH')


def unique_tap(xml, activity, window, spec, size=(480, 800)):
    foreground(activity, window, spec.component)
    if len(xml.encode()) > UI_LIMIT or '<!DOCTYPE' in xml or '<!ENTITY' in xml:
        raise Blocked('UI_XML_UNSAFE_OR_OVERSIZE')
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as e:
        raise Blocked('UI_XML_INVALID') from e
    if root.tag != 'hierarchy' or root.get('rotation') != '0':
        raise Blocked('UI_HIERARCHY_OR_ROTATION_UNKNOWN')
    nodes = list(root.iter('node'))
    if not nodes or len(nodes) > 1000:
        raise Blocked('UI_NODE_COUNT_UNKNOWN')
    visible = [n for n in nodes if n.get('visible-to-user') != 'false']
    if {n.get('package') for n in visible} != {spec.owner}:
        raise Blocked('UI_UNEXPECTED_OWNER_OR_OVERLAY')
    texts = Counter(n.get('text', '') for n in visible)
    if any(texts[t] != 1 for t in spec.required_texts):
        raise Blocked('UI_REQUIRED_TEXT_NOT_UNIQUE')
    def selector(n):
        return Selector(n.get('text', ''), n.get('resource-id', ''), n.get('class', ''))
    clickables = [n for n in visible if n.get('enabled') == 'true' and n.get('clickable') == 'true']
    if any(selector(n) not in spec.allowed_clickables for n in clickables):
        raise Blocked('UI_UNREVIEWED_ACTIONABLE_CONTROL')
    targets = [n for n in clickables if selector(n) == spec.target]
    if len(targets) != 1:
        raise Blocked('UI_TARGET_NOT_UNIQUE_ENABLED_VISIBLE')
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', targets[0].get('bounds', ''))
    if not bounds:
        raise Blocked('UI_TARGET_BOUNDS_UNKNOWN')
    left, top, right, bottom = map(int, bounds.groups())
    if not (0 <= left < right <= size[0] and 0 <= top < bottom <= size[1]):
        raise Blocked('UI_TARGET_OFFSCREEN_OR_EMPTY')
    return ((left + right) // 2, (top + bottom) // 2)


BEGIN = Selector('Begin muted capture test', '', 'android.widget.Button')
STOP = Selector('Stop now', '', 'android.widget.Button')
APP_BEGIN = UiSpec(PACKAGE, ACTIVITY, BEGIN, (BEGIN.text, STOP.text), (BEGIN, STOP), 'begin')

# These are deliberately absent. No fixture is actual API36 consent evidence.
# Fill only after separate authorized discovery, observed ownership/selectors,
# single-app scope semantics and explicit root review. No command-line override.
RECORD_AUDIO_UI = None
PROJECTION_UI_STEPS = None
ROOT_RUNTIME_REVIEW = None


def require_record_audio_review():
    if RECORD_AUDIO_UI is None:
        raise Blocked('UNREVIEWED_API36_RECORD_AUDIO_UI')
    record = RECORD_AUDIO_UI
    if (record.owner not in ('com.android.permissioncontroller', 'com.google.android.permissioncontroller') or
            record.component.split('/')[0] != record.owner or record.action != 'record-audio' or
            record.target.text != 'While using the app' or
            record.target.resource != record.owner + ':id/permission_allow_foreground_only_button' or
            ('Allow ' + LABEL + ' to record audio?') not in record.required_texts):
        raise Blocked('UNREVIEWED_OR_BROADER_RUNTIME_PERMISSION')
    return True


def require_ui_review():
    if ROOT_RUNTIME_REVIEW is None or RECORD_AUDIO_UI is None or PROJECTION_UI_STEPS is None:
        raise Blocked('UNREVIEWED_API36_CONSENT_UI')
    require_record_audio_review()
    actions = tuple(s.action for s in PROJECTION_UI_STEPS)
    if actions not in (('select-single-app', 'confirm-single-app', 'select-own-app'),
                       ('confirm-single-app', 'select-own-app')):
        raise Blocked('UNREVIEWED_OR_BROADER_PROJECTION_SCOPE')
    for step in PROJECTION_UI_STEPS:
        if step.owner != 'com.android.systemui' or step.component.split('/')[0] != step.owner:
            raise Blocked('PROJECTION_OWNER_UNKNOWN')
        if step.action == 'select-own-app' and (step.target.text != LABEL or LABEL not in step.required_texts):
            raise Blocked('PROJECTION_PICKER_NOT_OWN_APP')
        if step.action != 'select-own-app' and LABEL not in ' '.join(step.required_texts):
            raise Blocked('PROJECTION_REQUESTER_UNVERIFIED')
    return True


# Separate, mode-specific root approval; cannot enable capture or projection taps.
# Root may later set exactly {"mode": "discover-record" or "discover-projection",
# "parent": exact reviewed runtime parent, "scope": "consent-ui-only-no-capture"}.
DISCOVERY_REVIEW = {'mode': 'discover-record', 'parent': '4062451c315265c827161f7e3ac925ff8df39121', 'scope': 'consent-ui-only-no-capture'}
DISCOVERY_MODES = ('discover-record', 'discover-projection')


def require_discovery_review(mode, parent):
    if mode not in DISCOVERY_MODES or DISCOVERY_REVIEW != {
            'mode': mode, 'parent': parent, 'scope': 'consent-ui-only-no-capture'}:
        raise Blocked('UNREVIEWED_CONSENT_DISCOVERY_MODE')
    if mode == 'discover-projection':
        require_record_audio_review()
    return True


_DISCOVERY_TEXT_KEYS = {
    LABEL: 'probe-label',
    'Allow ' + LABEL + ' to record audio?': 'probe-record-audio-request',
    'Allow ' + LABEL + " to access this device's microphone?": 'probe-record-audio-request',
    'Start recording or casting with ' + LABEL + '?': 'probe-projection-request',
    'Share your screen with ' + LABEL + '?': 'probe-projection-request',
    'While using the app': 'foreground-only',
    'Only this time': 'one-time',
    "Don't allow": 'deny',
    'Cancel': 'cancel', 'Start now': 'start-now',
    'Start recording': 'start-recording', 'Start sharing': 'start-sharing',
    'Share screen': 'share-screen',
    'Single app': 'single-app', 'A single app': 'single-app', 'An app': 'single-app',
    'Entire screen': 'entire-screen', 'Your entire screen': 'entire-screen',
}
_DISCOVERY_CLASSES = {'android.widget.' + name for name in (
    'TextView', 'Button', 'Spinner', 'CheckedTextView', 'RadioButton', 'CheckBox',
    'ImageButton', 'ImageView', 'LinearLayout', 'FrameLayout', 'ScrollView', 'ListView')}
_DISCOVERY_CLASSES |= {'android.view.View', 'android.view.ViewGroup'}


def inspect_consent_dialog(xml, activity, window, mode, size=(480, 800)):
    """Read only. Return bounded allowlisted synthetic facts, never a tap target.

    Unknown text/content descriptions are never emitted. Ownership and one probe
    label occurrence are required even to publish the filtered structural facts.
    Actual observed resource IDs are limited to the official owner/android IDs;
    these facts do not approve selectors or imply that any scope may be granted.
    """
    if mode not in DISCOVERY_MODES:
        raise Blocked('UNKNOWN_CONSENT_DISCOVERY_MODE')
    component = exactly(r'^\s*mResumedActivity: ActivityRecord\{[^\n]+ u0 (\S+) t\d+\}?\s*$', activity, flags=re.M)
    owner, separator, activity_class = component.partition('/')
    owners = ('com.android.permissioncontroller', 'com.google.android.permissioncontroller') if mode == 'discover-record' else ('com.android.systemui',)
    suffix = '.GrantPermissionsActivity' if mode == 'discover-record' else '.MediaProjectionPermissionActivity'
    if (not separator or owner not in owners or not activity_class.endswith(suffix) or
            not re.fullmatch(r'[A-Za-z0-9_.]{1,180}', activity_class)):
        raise Blocked('UNRECOGNIZED_OFFICIAL_CONSENT_OWNER')
    foreground(activity, window, component)
    if len(xml.encode()) > UI_LIMIT or '<!DOCTYPE' in xml or '<!ENTITY' in xml:
        raise Blocked('UI_XML_UNSAFE_OR_OVERSIZE')
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise Blocked('UI_XML_INVALID') from error
    if root.tag != 'hierarchy' or root.get('rotation') != '0':
        raise Blocked('UI_HIERARCHY_OR_ROTATION_UNKNOWN')
    nodes = list(root.iter('node'))
    if not nodes or len(nodes) > 1000:
        raise Blocked('UI_NODE_COUNT_UNKNOWN')
    nodes = [n for n in nodes if n.get('visible-to-user') != 'false']
    if {n.get('package') for n in nodes} != {owner}:
        raise Blocked('UI_UNEXPECTED_OWNER_OR_OVERLAY')
    if sum(n.get('text', '').count(LABEL) for n in nodes) != 1:
        raise Blocked('DISCOVERY_PROBE_LABEL_NOT_UNIQUE')
    facts = []
    unknown_texts = unknown_controls = 0
    for node in nodes:
        text = node.get('text', '')
        key = _DISCOVERY_TEXT_KEYS.get(text)
        clickable = node.get('clickable') == 'true' and node.get('enabled') == 'true'
        if text and key is None:
            unknown_texts += 1
        if clickable and key is None:
            unknown_controls += 1
        if not key:
            continue
        resource = node.get('resource-id', '')
        # Unknown resources/classes are redacted, never promoted into selectors.
        resource_ok = bool(re.fullmatch(r'(?:' + re.escape(owner) + r'|android):id/[a-z0-9_]{1,96}', resource))
        class_ok = node.get('class') in _DISCOVERY_CLASSES
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        values = tuple(map(int, bounds.groups())) if bounds else None
        bounds_ok = values is not None and 0 <= values[0] < values[2] <= size[0] and 0 <= values[1] < values[3] <= size[1]
        fact = {'text_key': key, 'enabled': node.get('enabled') == 'true',
                'clickable': clickable, 'resource_known_format': resource_ok,
                'class_known': class_ok, 'bounds_verified': bounds_ok}
        if resource_ok: fact['resource_id'] = resource
        if class_ok: fact['class'] = node.get('class')
        if bounds_ok: fact['bounds'] = list(values)
        facts.append(fact)
    if len(facts) > 32:
        raise Blocked('CONSENT_FACT_COUNT_LIMIT')
    return {'kind': 'record-audio' if mode == 'discover-record' else 'media-projection',
            'owner': owner, 'foreground_component': component, 'probe_label_unique': True,
            'allowlisted_nodes': facts, 'redacted_text_nodes': unknown_texts,
            'unreviewed_clickable_nodes': unknown_controls,
            'entire_screen_option_visible': any(f['text_key'] == 'entire-screen' for f in facts),
            'consent_granted_by_discovery': False}
