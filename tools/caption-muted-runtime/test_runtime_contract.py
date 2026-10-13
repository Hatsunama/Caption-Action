"""Synthetic fixtures only. None of these selectors is observed API36 UI."""
import dataclasses
from pathlib import Path
import unittest
from unittest.mock import patch
import runtime_contract as c


# Reconstructed from full Android16 MediaShellCommand.onHelp + VolumeCtrl.USAGE.
# Source VolumeCtrl SHA256:8663f6736f0ede8d6869b902d09865271cfccd680ae70f11a629ec4bec639926
MEDIA_HELP_STDOUT = 'usage: media_session [subcommand] [options]\n       media_session dispatch KEY\n       media_session list-sessions\n       media_session monitor <tag>\n       media_session volume [options]\n       media_session expire-temp-engaged-sessions\n\nmedia_session dispatch: dispatch a media key to the system.\n                KEY may be: play, pause, play-pause, mute, headsethook,\n                stop, next, previous, rewind, record, fast-forward.\nmedia_session list-sessions: print a list of the current sessions.\nmedia_session monitor: monitor updates to the specified session.\n                       Use the tag from list-sessions.\nmedia_session volume:  the options are as follows: \n\t\t--stream STREAM selects the stream to control, see AudioManager.STREAM_*\n\t\t                controls AudioManager.STREAM_MUSIC if no stream is specified\n\t\t--set INDEX     sets the volume index value\n\t\t--adj DIRECTION adjusts the volume, use raise|same|lower for the direction\n\t\t--get           outputs the current volume\n\t\t--show          shows the UI during the volume change\n\texamples:\n\t\tadb shell media volume --show --stream 3 --set 11\n\t\tadb shell media volume --stream 0 --adj lower\n\t\tadb shell media volume --stream 3 --get\n\nmedia_session expire-temp-engaged-sessions: Expires any ongoing\n                timers for media sessions in a temporary user-engaged\n                state.\n\n'
# AudioManagerShellCommand android16-release SHA25634f20619184f26489469a3abc4dd5ac21dd909edad74fe33af43061755ce8377
AUDIO_HELP_STDOUT = 'Audio manager commands:\n  help\n    Print this help text.\n\n  set-surround-format-enabled SURROUND_FORMAT IS_ENABLED\n    Enables/disabled the SURROUND_FORMAT based on IS_ENABLED\n  get-is-surround-format-enabled SURROUND_FORMAT\n    Returns if the SURROUND_FORMAT is enabled\n  set-encoded-surround-mode SURROUND_SOUND_MODE\n    Sets the encoded surround sound mode to SURROUND_SOUND_MODE\n  get-encoded-surround-mode\n    Returns the encoded surround sound mode\n  set-sound-dose-value\n    Sets the current sound dose value\n  get-sound-dose-value\n    Returns the current sound dose value\n  reset-sound-dose-timeout\n    Resets the sound dose timeout used for momentary exposure\n  set-ringer-mode NORMAL|SILENT|VIBRATE\n    Sets the Ringer mode to one of NORMAL|SILENT|VIBRATE\n  set-volume STREAM_TYPE VOLUME_INDEX\n    Sets the volume for STREAM_TYPE to VOLUME_INDEX\n  get-min-volume STREAM_TYPE\n    Gets the min volume for STREAM_TYPE\n  get-max-volume STREAM_TYPE\n    Gets the max volume for STREAM_TYPE\n  get-stream-volume STREAM_TYPE\n    Gets the volume for STREAM_TYPE\n  set-device-volume STREAM_TYPE VOLUME_INDEX NATIVE_DEVICE_TYPE\n    Sets for NATIVE_DEVICE_TYPE the STREAM_TYPE volume to VOLUME_INDEX\n  adj-mute STREAM_TYPE\n    mutes the STREAM_TYPE\n  adj-unmute STREAM_TYPE\n    unmutes the STREAM_TYPE\n  adj-volume STREAM_TYPE <RAISE|LOWER|MUTE|UNMUTE>\n    Adjusts the STREAM_TYPE volume given the specified direction\n  set-group-volume GROUP_ID VOLUME_INDEX\n    Sets the volume for GROUP_ID to VOLUME_INDEX\n  adj-group-volume GROUP_ID <RAISE|LOWER|MUTE|UNMUTE>\n    Adjusts the group volume for GROUP_ID given the specified direction\n  set-enable-hardening <1|0>\n    Enables full audio hardening enforcement, disabling any exemptions\n'
AUDIO_SET_ZERO_STDOUT = 'calling AudioManager.setStreamVolume(3, 0, 0)\n'

VOLUME_SET_ZERO_STDOUT = ('[V] will control stream=3 (STREAM_MUSIC)\n'
                          '[V] will set volume to index=0\n'
                          '[V] Connecting to AudioService\n')


def volume_get_stdout(value=0, minimum=0, maximum=15):
    return ('[V] will control stream=3 (STREAM_MUSIC)\n'
            '[V] will get volume\n[V] Connecting to AudioService\n'
            f'[V] volume is {value} in range [{minimum}..{maximum}]\n')


def line(phase, rms='0.000', fraction='0.0000', peak=0):
    return f'{phase} result=PASS sourceFrames=96000 samples=64000 nonzero=0 rms={rms} peak={peak} toneFraction={fraction} sha256=' + 'a' * 64


def good_metrics():
    return '\n'.join((line('SILENT'), line('PERMITTED_TONE', '4200.000', '0.9900', 6000),
                       line('DENIED_TONE'), c.FINAL)) + '\n'


def audio_fixture():
    return ('\nAudio mode: \n- Requested mode = MODE_NORMAL\n- Actual mode = MODE_NORMAL\n'
            '- Mode owner: \n   None\n- Mode owner stack: \n   Empty\n'
            '\nAudio routes:\n  mMainType=0x0\n  mBluetoothName=null\n\nOther state:\n'
            '  mUseFixedVolume=false\n\n  players:\n\n  ducked players piids:\n'
            '\nRecordActivityMonitor dump time: 1:00 PM\n\n\n'
            'Events log: recording activity received by AudioService\nold historical event')


def policy_fixture():
    return '\n Available output devices (1):\n  1. Port ID: 2; "Speaker"; {AUDIO_DEVICE_OUT_SPEAKER, @:}\n\n Available input devices (1):\n'


def sessions_fixture():
    return 'MEDIA SESSION SERVICE (dumpsys media_session)\nGlobal priority session is null\n  Sessions Stack - have 0 sessions:\n'


def package_fixture(stopped='true', granted='false'):
    return (f'  Package [{c.PACKAGE}] (abc):\n    userId=10123\n'
            f'    User 0: installed=true stopped={stopped} hidden=false\n'
            f'      runtime permissions:\n        android.permission.RECORD_AUDIO: granted={granted}, flags=[]\n\n')


def ownership(component=c.ACTIVITY):
    return (f'  mResumedActivity: ActivityRecord{{abc u0 {component} t12}}\n',
            f'  mCurrentFocus=Window{{abc u0 {component}}}\n')


def app_xml(extra=''):
    return (f'<hierarchy rotation="0"><node package="{c.PACKAGE}" text="" enabled="true" clickable="false">'
            f'<node package="{c.PACKAGE}" text="Begin muted capture test" resource-id="" class="android.widget.Button" '
            'enabled="true" clickable="true" bounds="[20,500][460,560]" />'
            f'<node package="{c.PACKAGE}" text="Stop now" resource-id="" class="android.widget.Button" '
            'enabled="true" clickable="true" bounds="[20,580][460,640]" />' + extra + '</node></hierarchy>')


class Metrics(unittest.TestCase):
    def test_complete_three_phase_final(self):
        self.assertTrue(c.parse_metrics(good_metrics())['final_verified'])
        self.assertEqual(len(c.parse_metrics(good_metrics())['phases']), 3)

    def test_no_false_success_empty_partial_missing_final(self):
        for bad in ('', good_metrics().replace(c.FINAL, ''), good_metrics().replace(line('DENIED_TONE'), '')):
            with self.subTest(bad=bad), self.assertRaises(c.Blocked): c.parse_metrics(bad)
        self.assertFalse(c.parse_metrics('', complete=False)['final_verified'])

    def test_duplicates_order_and_contradictory_late_failure(self):
        cases = (good_metrics() + c.FINAL, good_metrics() + '\nABORT reason=unsafe_output_state',
                 good_metrics() + '\nfailed errorType=Native', good_metrics() + '\nteardown_unverified watchdogArmed=true',
                 good_metrics().replace('SILENT', 'DENIED_TONE'), c.FINAL + '\n' + good_metrics())
        for bad in cases:
            with self.subTest(bad=bad), self.assertRaises(c.Blocked): c.parse_metrics(bad)

    def test_threshold_nonfinite_unknown_and_scalar_bounds(self):
        for old, new in [('4200.000', '99.999'), ('0.9900', '0.6999'), ('rms=0.000', 'rms=2.000'),
                         ('rms=0.000', 'rms=NaN'), ('rms=0.000', 'rms=Infinity'),
                         ('samples=64000', 'samples=31999'), ('sourceFrames=96000', 'sourceFrames=63999'),
                         ('nonzero=0', 'nonzero=64001'), ('peak=6000', 'peak=99999'),
                         ('result=PASS', 'result=FAIL_INCONCLUSIVE'), ('mediaIndex=0', 'mediaIndex=1'),
                         ('teardownVerified=true', 'teardownVerified=false')]:
            with self.subTest(new=new), self.assertRaises(c.Blocked): c.parse_metrics(good_metrics().replace(old,new))
        with self.assertRaises(c.Blocked): c.parse_metrics('x' * 20000)


class GuestState(unittest.TestCase):
    def test_safe_current_audio_sections(self):
        self.assertTrue(c.verify_audio_idle(audio_fixture(), policy_fixture(), sessions_fixture()))

    def test_absent_speaker_external_modes_players_recorders_sessions_unknown(self):
        for audio, policy, sessions in [('', policy_fixture(), sessions_fixture()),
            (audio_fixture().replace('MODE_NORMAL','MODE_IN_CALL'), policy_fixture(), sessions_fixture()),
            (audio_fixture().replace('mUseFixedVolume=false','mUseFixedVolume=true'),policy_fixture(), sessions_fixture()),
            (audio_fixture().replace('mBluetoothName=null','mBluetoothName=wireless'),policy_fixture(), sessions_fixture()),
            (audio_fixture().replace('  players:\n','  players:\n player'),policy_fixture(), sessions_fixture()),
            (audio_fixture().replace('dump time: 1:00 PM\n','dump time: 1:00 PM\nriid 3; active? false'),policy_fixture(), sessions_fixture()),
            (audio_fixture(),policy_fixture().replace('AUDIO_DEVICE_OUT_SPEAKER','AUDIO_DEVICE_OUT_BLUETOOTH_A2DP'), sessions_fixture()),
            (audio_fixture(),policy_fixture().replace('devices (1)','devices (0)'), sessions_fixture()),
            (audio_fixture(),policy_fixture(),sessions_fixture().replace('have 0','have 1'))]:
            with self.subTest(audio=audio), self.assertRaises(c.Blocked): c.verify_audio_idle(audio,policy,sessions)

    def test_history_not_accepted_as_current_safe_state(self):
        bad = audio_fixture().replace('  players:\n', '  unknown_players:\n') + '\nold log: players empty'
        with self.assertRaises(c.Blocked): c.verify_audio_idle(bad,policy_fixture(),sessions_fixture())

    def test_projection_exact_null_only(self):
        good='MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull'
        c.verify_projection_idle(good)
        for bad in ('',good+'\nuid=10123',good.replace('null','projection'),good+'\n'+good):
            with self.assertRaises(c.Blocked): c.verify_projection_idle(bad)

    def test_volume_only_supported_and_zero_range(self):
        c.verify_volume_help(MEDIA_HELP_STDOUT)
        self.assertEqual(c.parse_volume(volume_get_stdout()),0)
        for bad in ('','[V] volume is 0 in range [1..15]','[E] failed\n[V] volume is 0 in range [0..15]',
                    '[V] volume is 0 in range [0..15]\n[V] volume is 1 in range [0..15]'):
            with self.assertRaises(c.Blocked): c.parse_volume(bad)

    def test_own_package_uid_permission_and_stopped_state(self):
        self.assertEqual(c.verify_package(package_fixture(),stopped=True,permission=False),10123)
        for bad in (package_fixture('false'),package_fixture().replace(c.PACKAGE,'other.app'),
                    package_fixture().replace('userId=10123','userId=1000'),package_fixture().replace('User 0','User 10')):
            with self.assertRaises(c.Blocked): c.verify_package(bad,stopped=True)
        with self.assertRaises(c.Blocked): c.verify_package(package_fixture(),permission=True)


class Consent(unittest.TestCase):
    def test_unique_actual_bounds_not_guessed(self):
        self.assertEqual(c.unique_tap(app_xml(),*ownership(),c.APP_BEGIN),(240,530))

    def test_wrong_foreground_package_activity_overlays_duplicate_disabled_bounds(self):
        for xml, own in [(app_xml(),ownership('foreign/.Activity')),
            (app_xml().replace(c.PACKAGE,'foreign'),ownership()),
            (app_xml().replace('clickable="true"','clickable="false"'),ownership()),
            (app_xml().replace('[20,500][460,560]','[0,0][9999,9999]'),ownership()),
            (app_xml().replace('rotation="0"','rotation="1"'),ownership()),
            (app_xml().replace('resource-id=""','resource-id="foreign:id/allow"'),ownership()),
            (app_xml().replace('enabled="true"','enabled="false"'),ownership()),
            (app_xml().replace('bounds="[20,500][460,560]"','visible-to-user="false" bounds="[20,500][460,560]"'),ownership()),
            (app_xml(extra=f'<node package="{c.PACKAGE}" text="Begin muted capture test" resource-id="" class="android.widget.Button" enabled="true" clickable="true" bounds="[1,1][2,2]" />'),ownership())]:
            with self.subTest(xml=xml), self.assertRaises(c.Blocked): c.unique_tap(xml,*own,c.APP_BEGIN)

    def test_unknown_extra_grant_control_and_xml_entities(self):
        extra=f'<node package="{c.PACKAGE}" text="Allow everything" resource-id="a" class="android.widget.Button" enabled="true" clickable="true" bounds="[1,1][2,2]" />'
        for xml in (app_xml(extra), '<!DOCTYPE hierarchy>'+app_xml(),app_xml()+'x','x'*300000):
            with self.assertRaises(c.Blocked): c.unique_tap(xml,*ownership(),c.APP_BEGIN)

    def test_live_review_gate_stays_none(self):
        self.assertIsNone(c.RECORD_AUDIO_UI);self.assertIsNone(c.PROJECTION_UI_STEPS);self.assertIsNone(c.ROOT_RUNTIME_REVIEW)
        with self.assertRaisesRegex(c.Blocked,'UNREVIEWED'): c.require_ui_review()

    def test_broader_or_unknown_projection_steps_rejected(self):
        owner='com.android.permissioncontroller'
        target=c.Selector('While using the app',owner+':id/permission_allow_foreground_only_button','android.widget.Button')
        record=c.UiSpec(owner,owner+'/.GrantPermissionsActivity',target,('Allow '+c.LABEL+' to record audio?',),(target,),'record-audio')
        bad=c.UiSpec('com.android.systemui','com.android.systemui/.Projection',target,(c.LABEL,),(target,),'entire-screen')
        with patch.object(c,'ROOT_RUNTIME_REVIEW','fixture'),patch.object(c,'RECORD_AUDIO_UI',record),patch.object(c,'PROJECTION_UI_STEPS',(bad,)):
            with self.assertRaisesRegex(c.Blocked,'BROADER_PROJECTION'):c.require_ui_review()



class AudioDiagnosticParsers(unittest.TestCase):
    def test_full_source_help_and_fixed_reads(self):
        c.verify_audio_help(AUDIO_HELP_STDOUT)
        self.assertEqual(len(AUDIO_HELP_STDOUT.encode()),1731)
        for command,method in [('get-min-volume','getStreamMinVolume'),('get-max-volume','getStreamMaxVolume'),('get-stream-volume','getStreamVolume')]:
            self.assertEqual(c.parse_audio_volume(command,f'AudioManager.{method}(3) -> 7\n'),7)
        c.verify_audio_set_zero(AUDIO_SET_ZERO_STDOUT)
        self.assertEqual(c.parse_volume_state(volume_get_stdout(7)),{'value':7,'min':0,'max':15})

    def test_unknown_empty_extra_malformed_channels_are_not_supported(self):
        for value in ('',AUDIO_HELP_STDOUT+'private',AUDIO_HELP_STDOUT.replace('get-min-volume','other'),AUDIO_HELP_STDOUT*2):
            with self.assertRaises(c.Blocked):c.verify_audio_help(value)
        for value in ('','AudioManager.getStreamVolume(3) -> -1\n','AudioManager.getStreamVolume(0) -> 7\n',
                      'AudioManager.getStreamVolume(3) -> 2147483648\n',
                      'AudioManager.getStreamVolume(3) -> 7\nprivate',
                      'AudioManager.getStreamVolume(3) -> 7\n\n'):
            with self.assertRaises(c.Blocked):c.parse_audio_volume('get-stream-volume',value)
        with self.assertRaises(c.Blocked):c.parse_audio_volume('private-command','private')
        for value in ('',AUDIO_SET_ZERO_STDOUT+'private',AUDIO_SET_ZERO_STDOUT.replace('3, 0','3, 1')):
            with self.assertRaises(c.Blocked):c.verify_audio_set_zero(value)


if __name__ == '__main__': unittest.main(verbosity=2)
