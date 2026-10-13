"""Diagnostic-only UID isolation. Other UID activity is UNKNOWN, never idle.

The native positive UID and usage criteria intersect. This guard does not
weaken parse_metrics, source route checks, recorder or projection checks.
"""
import re
import runtime_contract as c
from boot_probe import Blocked
UNSIGNED_SHA256='dda71638acd519f8ec37c6a31738c63c4ffda664bdfff33e0003c5e7fb63761f'

def verify_binding(binding):
    expected={'artifact_source_bound':True,'unsigned_sha256':UNSIGNED_SHA256,
              'uid_rules':['Process.myUid()'],'usage_rules':['USAGE_MEDIA'],
              'mic_fallback':False,'shared_user_id':False,'internet':False,
              'camera':False,'virtual_display':False}
    if type(binding) is not dict or binding != expected:
        raise Blocked('ARTIFACT_SOURCE_UID_USAGE_BINDING_UNPROVEN')
    # bool/int equality must not allow a fabricated nonboolean proof.
    if any(type(binding[k]) is not type(v) for k,v in expected.items()):
        raise Blocked('ARTIFACT_SOURCE_UID_USAGE_BINDING_UNPROVEN')


def verify_unique_uid(uid, package_uid_rows):
    if type(uid) is not int or not 10000 <= uid < 20000:
        raise Blocked('PROBE_UID_MISSING_OR_UNKNOWN')
    if (type(package_uid_rows) is not list or not package_uid_rows or
            any(type(row) not in (tuple,list) or len(row)!=2 or type(row[0]) is not str or
                type(row[1]) is not int for row in package_uid_rows)):
        raise Blocked('PACKAGE_UID_INVENTORY_UNKNOWN')
    if [tuple(row) for row in package_uid_rows if row[0]==c.PACKAGE or row[1]==uid] != [(c.PACKAGE,uid)]:
        raise Blocked('PROBE_UID_NOT_UNIQUELY_OWNED')


def parse_package_uid_rows(text):
    if not text or len(text.encode())>262144:
        raise Blocked('PACKAGE_UID_INVENTORY_UNKNOWN')
    rows=[]
    for line in text.splitlines():
        m=re.fullmatch(r'package:([A-Za-z0-9_.]+) uid:([0-9]{1,10})',line)
        if not m: raise Blocked('PACKAGE_UID_INVENTORY_UNKNOWN')
        rows.append((m[1],int(m[2])))
    if len({r[0] for r in rows})!=len(rows):raise Blocked('PACKAGE_UID_INVENTORY_AMBIGUOUS')
    return rows


def verify_actual_track_route(expected_speaker_id, actual_id, actual_type):
    if (type(expected_speaker_id) is not int or expected_speaker_id<=0 or
            type(actual_id) is not int or actual_id!=expected_speaker_id or
            type(actual_type) is not int or actual_type!=2):
        raise Blocked('WRONG_ACTUAL_AUDIOTRACK_ROUTE')
    return True


def verify_probe_isolation(audio, policy, sessions, *, uid, package_uid_rows,
                           binding, owned_emulator, audio_volume, media_volume):
    verify_binding(binding)
    verify_unique_uid(uid,package_uid_rows)
    if owned_emulator is not True:raise Blocked('OWNED_EMULATOR_IDENTITY_UNPROVEN')
    if any(type(v) is not int or v!=0 for v in (audio_volume,media_volume)):
        raise Blocked('INDEPENDENT_MEDIA_VOLUME_NOT_ZERO')
    if any(len(t.encode())>262144 for t in (audio,policy,sessions)):
        raise Blocked('AUDIO_INVENTORY_LIMIT')
    players=c.section(audio,r'\n  players:\n',r'\n  ducked players piids:')
    lines=[x for x in players.splitlines() if x.strip()]
    if len(lines)>32 or len(players.encode())>16384:raise Blocked('PLAYER_INVENTORY_LIMIT')
    others=[]; piids=set()
    for line in lines:
        m=re.fullmatch(r'(?:\(not logged\))?  AudioPlaybackConfiguration piid:(-?[0-9]{1,10}) '
           r'deviceIds:\[[0-9, -]*\] type:([A-Za-z0-9_.$]+) u/pid:([0-9]{1,10})/([0-9]{1,10}) '
           r'state:([a-z ]{1,30}) attr:AudioAttributes: usage=(USAGE_[A-Z0-9_]+) [^\n]{0,1600}',line)
        if not m:raise Blocked('PLAYER_UID_MAPPING_UNKNOWN')
        piid,kind,player_uid,pid,state,usage=m.groups()
        if not 0<=int(player_uid)<=2147483647 or not 0<int(pid)<=2147483647:raise Blocked('PLAYER_UID_OR_PID_UNKNOWN')
        if int(piid) in piids:raise Blocked('PLAYER_UID_MAPPING_AMBIGUOUS')
        piids.add(int(piid))
        if int(player_uid)==uid:raise Blocked('UNEXPECTED_OWN_UID_PLAYER')
        others.append({'uid':int(player_uid),'type':kind,'activity':'UNKNOWN',
                       'excluded_by_uid':True,'soundpool_state_reporting_incomplete':kind=='android.media.SoundPool'})
    count,outputs=c.exactly(r'\n Available output devices \((\d+)\):\n(.*?)\n Available input devices',policy,flags=re.S)
    entries=[]
    if len(outputs.encode())>16384 or len(outputs.splitlines())>128:raise Blocked('OUTPUT_INVENTORY_LIMIT')
    for line in outputs.splitlines():
        if not line.strip():continue
        # DeviceVector::dump(spaces=1) gives top-level exactly two spaces;
        # DeviceDescriptorBase and AudioPort profile details start at >=5.
        # Nested details are inert metadata, never an actual selected route.
        if re.match(r'^ {5}\S|^ {6,}',line):
            if not entries or 'AUDIO_DEVICE_OUT_' in line or 'Port ID:' in line:raise Blocked('NESTED_OUTPUT_IDENTITY_UNKNOWN')
            continue
        m=re.fullmatch(r'  ([0-9]+)\. Port ID: ([0-9]+); "[^"\n]*"; \{(AUDIO_DEVICE_OUT_[A-Z0-9_]+), @:[^}\n]*\}',line)
        if not m:raise Blocked('OUTPUT_INVENTORY_UNKNOWN')
        entries.append((int(m[1]),int(m[2]),m[3]))
    allowed={'AUDIO_DEVICE_OUT_SPEAKER','AUDIO_DEVICE_OUT_EARPIECE','AUDIO_DEVICE_OUT_TELEPHONY_TX'}
    speakers=[e[1] for e in entries if e[2]=='AUDIO_DEVICE_OUT_SPEAKER']
    if (len(entries)!=int(count) or len(speakers)!=1 or any(e[2] not in allowed for e in entries) or
            any(not 0<e[1]<=2147483647 for e in entries) or
            len({e[1] for e in entries})!=len(entries) or [e[0] for e in entries]!=list(range(1,len(entries)+1))):
        raise Blocked('BUILTIN_SPEAKER_UNAVAILABLE_OR_EXTERNAL_OUTPUT')
    priority=c.exactly(r'^Global priority session is ([^\n]{1,512})$',sessions,flags=re.M)
    # Reuse every historical mode/fixed-volume/route/recorder/session-stack guard.
    # Only already-parsed OTHER UID player objects, priority presence and passive
    # Telephony TX inventory are normalized. Actual track routing is never here.
    clean_audio=audio.replace('\n  players:\n'+players+'\n  ducked players piids:', '\n  players:\n\n  ducked players piids:',1)
    kept=[e for e in entries if e[2]!='AUDIO_DEVICE_OUT_TELEPHONY_TX']
    clean_outputs=''.join(f'  {i+1}. Port ID: {e[1]}; "Built-in"; {{{e[2]}, @:}}\n' for i,e in enumerate(kept))
    clean_policy=policy.replace(f'\n Available output devices ({count}):\n'+outputs+'\n Available input devices',f'\n Available output devices ({len(kept)}):\n'+clean_outputs+'\n Available input devices',1)
    clean_sessions=sessions.replace('Global priority session is '+priority,'Global priority session is null',1)
    c.verify_audio_idle(clean_audio,clean_policy,clean_sessions)
    return {'other_uid_players':others,'priority_session':'absent' if priority=='null' else 'present_quality_unknown',
            'speaker_port_id':speakers[0],'passive_telephony_inventory_only':any(e[2]=='AUDIO_DEVICE_OUT_TELEPHONY_TX' for e in entries),
            'inventory_verified':True,'runtime_pass':False,'capture_authorized':False}
