"""Control-flow and static invariants without SDK, real ADB, guest, or signed bytes."""
from pathlib import Path
import tempfile
import unittest
from test_runtime_contract import good_metrics, package_fixture
from unittest.mock import Mock, patch
import artifact_gate
import capture_runtime as r
import boot_probe as b
import runtime_contract as c


class Runtime(unittest.TestCase):
    def env(self):
        return dict(PROBE_REPOSITORY_PRIVATE='false',PROBE_REPOSITORY_VISIBILITY='public',
          GITHUB_ACTIONS='true',RUNNER_ENVIRONMENT='github-hosted',GITHUB_REPOSITORY='Hatsunama/Caption-Action',
          GITHUB_EVENT_NAME='push',GITHUB_REF=b.BRANCH,ImageOS='ubuntu24',RUNNER_OS='Linux',RUNNER_ARCH='X64',
          PROBE_COMMIT='a'*40,PROBE_CELL='api36')

    def test_absent_signed_identity_fails_before_subprocess(self):
        with patch.object(artifact_gate,'SIGNED_IDENTITY',None),patch.object(r.subprocess,'check_output') as cmd,self.assertRaises(artifact_gate.ArtifactGateError):r.preflight(self.env())
        cmd.assert_not_called()

    def test_unknown_ui_fails_before_git_sdk_or_download(self):
        with patch.object(artifact_gate,'require_approved_identity'),patch.object(r.subprocess,'check_output') as cmd:
            with self.assertRaisesRegex(c.Blocked,'UNREVIEWED'):r.preflight(self.env())
            cmd.assert_not_called()

    def test_exact_single_parent_no_merge_or_different_delivery(self):
        for actual,parents in [('a'*40,b.PROBE_PARENT),('b'*40,b.PROBE_PARENT),('a'*40,'b'*40),('a'*40,b.PROBE_PARENT+' '+'c'*40)]:
            with patch.object(artifact_gate,'require_approved_identity'),patch.object(c,'require_ui_review'),patch.object(r.subprocess,'check_output',side_effect=[actual,parents]):
                if actual=='a'*40 and parents==b.PROBE_PARENT:self.assertTrue(r.preflight(self.env()))
                else:
                    with self.assertRaises(c.Blocked):r.preflight(self.env())

    def test_disarm_requires_verified_stopped_and_live_watchdog(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.watchdog=Mock(poll=Mock(return_value=None))
            with patch.object(r.os,'write') as write,self.assertRaises(c.Blocked):obj.disarm_watchdog()
            write.assert_not_called()

    def test_boot_alone_never_capture_pass(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.result.update(status='environment-boot-verified',page_size_verified=True)
            self.assertTrue(obj.cleanup());self.assertFalse(obj.result['runtime_pass'])

    def test_cleanup_failure_invalidates_pending_pass(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.result['status']='capture-verified-pending-host-cleanup'
            with patch.object(obj.kvm,'release',side_effect=RuntimeError('fixture')):
                self.assertFalse(obj.cleanup())
            self.assertEqual(obj.result['status'],'cleanup-failed');self.assertFalse(obj.result['runtime_pass'])

    def test_only_verified_capture_and_host_cleanup_can_publish_pass(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.result['status']='capture-verified-pending-host-cleanup'
            self.assertTrue(obj.cleanup());self.assertTrue(obj.result['runtime_pass']);self.assertEqual(obj.result['status'],'PASS')

    def test_source_watchdog_precedes_second_begin_and_final_parse_follows_stop(self):
        text=Path(r.__file__).read_text()
        segment=text[text.index('self.arm_watchdog()'):]
        self.assertLess(segment.index('self.tap(contract.APP_BEGIN)'),segment.index('for step in'))
        self.assertLess(segment.index('self.force_stopped()'),segment.index('metrics = contract.parse_metrics(log.read_text())'))
        self.assertLess(segment.index('metrics = contract.parse_metrics(log.read_text())'),segment.index('self.disarm_watchdog()'))
        for bad in ('pm grant','appops','PROJECT_MEDIA','keytool','createScreenCaptureIntent','-g\'',"'--set', '1'"):
            self.assertNotIn(bad,text)
        self.assertIn("'--set', '0'",text)

    def test_mocked_capture_flow_orders_watchdog_teardown_and_uninstall(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);obj=r.CaptureRun(root,root);obj.work=root
            obj.adb='fixture-adb';obj.server=Mock(poll=Mock(return_value=None));obj.emulator=Mock(poll=Mock(return_value=None))
            obj.watchdog=Mock(poll=Mock(return_value=None));artifact=Mock(apk_path='/fixture/verified.apk')
            trace=[];packages=[package_fixture(),package_fixture(granted='true')]
            log=root/'capture-scalars.txt';log.write_text(good_metrics())
            capture=Mock(poll=Mock(return_value=None))
            def shell(args,**kw):
                if args==['getprop','persist.sys.locale']:return 'en-US'
                if args==['wm','size']:return 'Physical size: 480x800'
                if args==['pm','list','packages',c.PACKAGE]:return ''
                if args==['media','help']:return '--stream STREAM AudioManager.STREAM_MUSIC --set INDEX --get'
                if args==['media','volume','--stream','3','--get']:return '[V] volume is 0 in range [0..15]'
                if args==['media','volume','--stream','3','--set','0']:trace.append('set-zero');return ''
                if args==['dumpsys','package',c.PACKAGE]:return packages.pop(0)
                if args==['am','start','-W','-n',c.ACTIVITY]:return 'Status: ok'
                if args==['pidof',c.PACKAGE]:return '234'
                if args==['ps','-A','-o','UID,PID,NAME']:return 'UID PID NAME\n10123 234 '+c.PACKAGE
                if args==['dumpsys','activity','services',c.PACKAGE]:return 'ACTIVITY MANAGER SERVICES (dumpsys activity services)\n  (nothing)'
                raise AssertionError(args)
            def adb(args,**kw):trace.append(args[0]);return 'Success'
            record=Mock(action='record-audio');projection=Mock(action='select-own-app')
            def stop():trace.append('force-stop');obj.verified_stopped=True
            with patch.object(b.Probe,'execute'),patch.object(obj,'shell',side_effect=shell),patch.object(obj,'adb_command',side_effect=adb),patch.object(obj,'assert_idle',side_effect=lambda:trace.append('idle')),patch.object(obj,'tap',side_effect=lambda spec:trace.append(spec.action)),patch.object(obj,'spawn',return_value=(capture,log)),patch.object(obj,'arm_watchdog',side_effect=lambda:trace.append('arm')),patch.object(obj,'force_stopped',side_effect=stop),patch.object(obj,'stop',side_effect=lambda p:trace.append('stop-collector')),patch.object(obj,'disarm_watchdog',side_effect=lambda:trace.append('disarm')),patch.object(c,'RECORD_AUDIO_UI',record),patch.object(c,'PROJECTION_UI_STEPS',(projection,)):
                obj.execute_capture(artifact)
            self.assertEqual(trace.count('begin'),2)
            self.assertLess(trace.index('arm'),[i for i,x in enumerate(trace) if x=='begin'][1])
            self.assertLess(trace.index('force-stop'),trace.index('stop-collector'))
            self.assertLess(trace.index('stop-collector'),trace.index('disarm'))
            self.assertLess(trace.index('disarm'),trace.index('uninstall'))
            self.assertEqual(artifact.recheck.call_count,2)
            self.assertFalse(obj.result['runtime_pass'])
            self.assertEqual(obj.result['status'],'capture-verified-pending-host-cleanup')

    def test_workflow_public_free_exact_pins_and_early_guard(self):
        text=(r.ROOT/'.github/workflows/caption-muted-runtime.yml').read_text()
        for good in ('runs-on: ubuntu-24.04','timeout-minutes: 25','contents: read','persist-credentials: false',
                     "github.repository == 'Hatsunama/Caption-Action'",'PROBE_CELL: api36',
                     'branches: [probe/caption-muted-runtime-20261013]',
                     'actions/checkout@11d5960a326750d5838078e36cf38b85af677262'):
            self.assertIn(good,text)
        self.assertLess(text.index('--preflight-only'),text.index('sudo -n apt-get'))
        for bad in ('workflow_dispatch:','pull_request:','secrets.','cache@','upload-artifact@','self-hosted','larger-runner','matrix:'):
            self.assertNotIn(bad,text)
        self.assertEqual(b.CELLS,{'api36':('36','google_apis','7','4096')})
        self.assertEqual(b.PROBE_PARENT,'4062451c315265c827161f7e3ac925ff8df39121')
        source=Path(b.__file__).read_text()
        self.assertIn("'-no-audio'",source);self.assertNotIn('sign_kvm_probe',source)
        self.assertNotIn('Hatsunama/sign-to-speak',source)


if __name__=='__main__':unittest.main(verbosity=2)
