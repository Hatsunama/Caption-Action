"""Synthetic fixtures only. None of these selectors is observed API36 UI."""
import dataclasses
from pathlib import Path
import unittest
from unittest.mock import patch
import runtime_contract as c


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
        c.verify_volume_help('--stream STREAM AudioManager.STREAM_MUSIC --set INDEX --get')
        self.assertEqual(c.parse_volume('[V] volume is 0 in range [0..15]'),0)
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


if __name__ == '__main__': unittest.main(verbosity=2)
