"""Two-stage consent discovery; synthetic fixtures only, no real SDK/ADB/guest."""
import dataclasses
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import artifact_gate
import boot_probe as b
import capture_runtime as r
import runtime_contract as c
import test_capture_runtime as capture_tests
from test_runtime_contract import package_fixture, ownership


def approved(mode):
    return {'mode':mode,'parent':b.PROBE_PARENT,'scope':'consent-ui-only-no-capture'}


def fixture(mode='discover-record', extra_text='private@example.invalid'):
    owner='com.google.android.permissioncontroller' if mode=='discover-record' else 'com.android.systemui'
    activity='.permission.ui.GrantPermissionsActivity' if mode=='discover-record' else '.mediaprojection.permission.MediaProjectionPermissionActivity'
    component=owner+'/'+activity
    title='Allow '+c.LABEL+' to record audio?' if mode=='discover-record' else 'Share your screen with '+c.LABEL+'?'
    button='While using the app' if mode=='discover-record' else 'Start sharing'
    xml=(f'<hierarchy rotation="0"><node package="{owner}" class="android.widget.LinearLayout" text="" enabled="true" clickable="false">'
         f'<node package="{owner}" class="android.widget.TextView" resource-id="{owner}:id/permission_message" text="{title}" enabled="true" clickable="false" bounds="[20,200][460,300]"/>'
         f'<node package="{owner}" class="android.widget.Button" resource-id="{owner}:id/permission_allow_foreground_only_button" text="{button}" enabled="true" clickable="true" bounds="[20,400][460,460]"/>'
         f'<node package="{owner}" class="private.Class" resource-id="private-resource" text="{extra_text}" content-desc="never-emit-this-description" enabled="true" clickable="true" bounds="unknown"/>'
         '</node></hierarchy>')
    return xml,*ownership(component)


def record_spec():
    owner='com.google.android.permissioncontroller'
    target=c.Selector('While using the app',owner+':id/permission_allow_foreground_only_button','android.widget.Button')
    return c.UiSpec(owner,owner+'/.permission.ui.GrantPermissionsActivity',target,
                    ('Allow '+c.LABEL+' to record audio?',),(target,),'record-audio')


class DiscoveryContracts(unittest.TestCase):
    def test_discovery_gate_defaults_none_and_cannot_enable_capture(self):
        with patch.object(c,'DISCOVERY_REVIEW',None):
            for mode in c.DISCOVERY_MODES:
                with self.assertRaises(c.Blocked):c.require_discovery_review(mode,b.PROBE_PARENT)
        with patch.object(c,'DISCOVERY_REVIEW',approved('discover-record')):
            self.assertTrue(c.require_discovery_review('discover-record',b.PROBE_PARENT))
            for mode,parent in [('capture',b.PROBE_PARENT),('discover-projection',b.PROBE_PARENT),('discover-record','a'*40)]:
                with self.assertRaises(c.Blocked):c.require_discovery_review(mode,parent)

    def test_projection_discovery_needs_reviewed_record_permission_only(self):
        with patch.object(c,'DISCOVERY_REVIEW',approved('discover-projection')):
            with self.assertRaisesRegex(c.Blocked,'RECORD_AUDIO'):c.require_discovery_review('discover-projection',b.PROBE_PARENT)
            with patch.object(c,'RECORD_AUDIO_UI',record_spec()):
                self.assertTrue(c.require_discovery_review('discover-projection',b.PROBE_PARENT))
                self.assertIsNone(c.PROJECTION_UI_STEPS)
                self.assertIsNone(c.ROOT_RUNTIME_REVIEW)

    def test_unreviewed_grant_variants_rejected(self):
        for spec in [dataclasses.replace(record_spec(),action='other'),
                     dataclasses.replace(record_spec(),target=c.Selector('Allow','foreign:id/allow','android.widget.Button')),
                     dataclasses.replace(record_spec(),required_texts=('Allow other app to record audio?',))]:
            with patch.object(c,'RECORD_AUDIO_UI',spec),self.assertRaises(c.Blocked):c.require_record_audio_review()

    def test_discovery_preflight_still_stops_at_missing_signed_identity(self):
        with patch.object(artifact_gate,'SIGNED_IDENTITY',None),patch.object(c,'DISCOVERY_REVIEW',approved('discover-record')),patch.object(r.subprocess,'check_output') as command:
            with self.assertRaises(artifact_gate.ArtifactGateError):r.preflight(capture_tests.Runtime().env(),'discover-record')
            command.assert_not_called()

    def test_discovery_preflight_does_not_require_unknown_grant_selectors(self):
        with patch.object(c,'DISCOVERY_REVIEW',approved('discover-record')),patch.object(artifact_gate,'require_approved_identity'),patch.object(r.subprocess,'check_output',side_effect=['a'*40,r.CHECKOUT_PARENT]):
            self.assertTrue(r.preflight(capture_tests.Runtime().env(),'discover-record'))
        self.assertIsNone(c.RECORD_AUDIO_UI);self.assertIsNone(c.PROJECTION_UI_STEPS)

    def test_allowlisted_facts_contain_no_raw_unknown_text_or_descriptions(self):
        facts=c.inspect_consent_dialog(*fixture(),'discover-record')
        output=json.dumps(facts)
        for private in ('private@example.invalid','never-emit-this-description','private.Class','private-resource'):
            self.assertNotIn(private,output)
        self.assertEqual(facts['redacted_text_nodes'],1)
        self.assertEqual(facts['unreviewed_clickable_nodes'],1)
        self.assertTrue(facts['probe_label_unique']);self.assertFalse(facts['consent_granted_by_discovery'])
        self.assertEqual(facts['allowlisted_nodes'][1]['text_key'],'foreground-only')
        self.assertNotIn('tap',facts)

    def test_projection_broader_scope_is_observed_but_never_approved(self):
        facts=c.inspect_consent_dialog(*fixture('discover-projection','Entire screen'),'discover-projection')
        self.assertTrue(facts['entire_screen_option_visible'])
        self.assertFalse(facts['consent_granted_by_discovery'])
        self.assertEqual(facts['kind'],'media-projection')

    def test_missing_or_duplicate_probe_label_unknown_owner_or_overlay_blocks(self):
        xml,activity,window=fixture()
        cases=[(xml.replace(c.LABEL,'other app'),activity,window),
               (xml.replace('private@example.invalid',c.LABEL),activity,window),
               (xml,activity.replace('GrantPermissionsActivity','OtherActivity'),window.replace('GrantPermissionsActivity','OtherActivity')),
               (xml.replace('package="com.google.android.permissioncontroller"','package="unrelated"',1),activity,window),
               (xml,*ownership('foreign/.GrantPermissionsActivity'))]
        for args in cases:
            with self.subTest(args=args),self.assertRaises(c.Blocked):c.inspect_consent_dialog(*args,'discover-record')

    def test_unknown_xml_resource_class_bounds_are_blocked_or_redacted(self):
        xml,activity,window=fixture()
        for bad in ('<!DOCTYPE hierarchy>'+xml,xml+'bad',xml.replace('rotation="0"','rotation="1"'),'x'*300000):
            with self.assertRaises(c.Blocked):c.inspect_consent_dialog(bad,activity,window,'discover-record')
        facts=c.inspect_consent_dialog(xml.replace('[20,400][460,460]','[0,0][99999,99999]'),activity,window,'discover-record')
        self.assertFalse(facts['allowlisted_nodes'][1]['bounds_verified'])
        self.assertNotIn('bounds',facts['allowlisted_nodes'][1])


class DiscoveryFlow(unittest.TestCase):
    def run_mock(self,mode,unknown_dialog=False):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);obj=r.CaptureRun(root,root);obj.work=root;trace=[]
            obj.watchdog=Mock(poll=Mock(return_value=None))
            def shell(args,**kw):
                if args==['dumpsys','package',c.PACKAGE]:return package_fixture(granted='true' if mode=='discover-projection' else 'false')
                if args==['dumpsys','activity','services',c.PACKAGE]:return 'ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)'
                if args==['pm','list','packages',c.PACKAGE]:return ''
                raise AssertionError(args)
            def stop():trace.append('force-stop');obj.verified_stopped=True
            observed=fixture(mode)
            if unknown_dialog:observed=(observed[0].replace(c.LABEL,'other app'),*observed[1:])
            with patch.object(c,'DISCOVERY_REVIEW',approved(mode)),patch.object(c,'RECORD_AUDIO_UI',record_spec()),patch.object(obj,'prepare_guest',side_effect=lambda artifact:trace.append('prepare')),patch.object(obj,'arm_watchdog',side_effect=lambda:trace.append('arm')),patch.object(obj,'tap',side_effect=lambda spec:trace.append(spec.action)),patch.object(obj,'snapshot_ui',return_value=observed),patch.object(obj,'shell',side_effect=shell),patch.object(obj,'assert_idle',side_effect=lambda:trace.append('idle')),patch.object(obj,'force_stopped',side_effect=stop),patch.object(obj,'disarm_watchdog',side_effect=lambda:trace.append('disarm')),patch.object(obj,'adb_command',side_effect=lambda args,**kw:trace.append(args[0]) or 'Success'),patch.object(obj,'execute_capture') as capture,patch.object(obj,'spawn') as spawn:
                if unknown_dialog:
                    with self.assertRaises(c.Blocked):obj.execute_discovery(Mock(),mode)
                else:obj.execute_discovery(Mock(),mode)
                capture.assert_not_called();spawn.assert_not_called()
            return trace,obj.result

    def test_first_stage_stops_without_any_permission_grant(self):
        trace,result=self.run_mock('discover-record')
        self.assertEqual(trace,['prepare','arm','begin','force-stop','disarm','uninstall'])
        self.assertFalse(result['record_audio_granted']);self.assertFalse(result['projection_granted'])
        self.assertFalse(result['capture_started']);self.assertFalse(result['runtime_pass'])
        self.assertEqual(result['status'],'discovery-stopped-pending-host-cleanup')

    def test_second_stage_only_exact_record_grant_no_projection_tap(self):
        trace,result=self.run_mock('discover-projection')
        self.assertEqual(trace,['prepare','arm','begin','record-audio','idle','begin','force-stop','disarm','uninstall'])
        self.assertTrue(result['record_audio_granted']);self.assertFalse(result['projection_granted'])
        self.assertFalse(result['capture_started']);self.assertFalse(result['runtime_pass'])

    def test_unknown_dialog_never_retries_taps_or_grants(self):
        for mode in c.DISCOVERY_MODES:
            trace,result=self.run_mock(mode,unknown_dialog=True)
            self.assertNotIn('select-single-app',trace);self.assertNotIn('select-own-app',trace)
            self.assertNotIn('disarm',trace)
            self.assertFalse(result['runtime_pass'])

    def test_successful_discovery_cleanup_never_publishes_runtime_pass(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.result['status']='discovery-stopped-pending-host-cleanup'
            self.assertTrue(obj.cleanup());self.assertEqual(obj.result['status'],'DISCOVERY_STOPPED')
            self.assertFalse(obj.result['runtime_pass']);self.assertTrue(obj.result['discovery_stopped_verified'])

    def test_discovery_cleanup_failure_cannot_be_success(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.result['status']='discovery-stopped-pending-host-cleanup'
            with patch.object(obj.kvm,'release',side_effect=RuntimeError('fixture')):self.assertFalse(obj.cleanup())
            self.assertEqual(obj.result['status'],'cleanup-failed');self.assertFalse(obj.result['runtime_pass'])

    def test_workflow_selects_only_idle_diagnostic_and_existing_signed_delivery_paths(self):
        text=(r.ROOT/'.github/workflows/caption-muted-runtime.yml').read_text()
        self.assertEqual(text.count('capture_runtime.py --mode diagnose-idle'),2)
        self.assertNotIn('--mode capture',text);self.assertNotIn('--mode discover-projection',text)
        self.assertNotIn('--mode diagnose-volume',text);self.assertNotIn('--mode discover-record',text)
        source=Path(r.__file__).read_text()
        self.assertIn("ROOT / 'tools/muted-capture-probe/artifacts/caption-muted-platform-probe-signed.apk'",source)
        self.assertIn("ROOT / 'tools/muted-capture-probe/SIGNING_RECEIPT.json'",source)
        discovery=source[source.index('    def execute_discovery'):source.index('    def cleanup')]
        self.assertNotIn('PROJECTION_UI_STEPS',discovery);self.assertNotIn('logcat',discovery)


if __name__=='__main__':unittest.main(verbosity=2)
