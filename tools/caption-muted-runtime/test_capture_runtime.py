"""Control-flow and static invariants without SDK, real ADB, guest, or signed bytes."""
from pathlib import Path
import tempfile
import json
import subprocess
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

    def test_timeout_keeps_only_safe_command_identity(self):
        with tempfile.TemporaryDirectory() as td:
            obj=r.CaptureRun(Path(td),Path(td));obj.adb='fixture-adb'
            obj.server=Mock(poll=Mock(return_value=None));obj.emulator=Mock(poll=Mock(return_value=None))
            with patch.object(obj,'command',side_effect=c.Blocked('COMMAND_TIMEOUT_OR_OUTPUT_LIMIT')):
                with self.assertRaises(c.Blocked):obj.adb_command(['shell','cmd','media_session','help'])
            self.assertEqual(obj.result['last_command_label'],'media_session_help')
            self.assertNotIn('fixture-adb',json.dumps(obj.result))

    def test_absent_signed_identity_fails_before_subprocess(self):
        with patch.object(artifact_gate,'SIGNED_IDENTITY',None),patch.object(r.subprocess,'check_output') as cmd,self.assertRaises(artifact_gate.ArtifactGateError):r.preflight(self.env())
        cmd.assert_not_called()

    def test_unknown_ui_fails_before_git_sdk_or_download(self):
        with patch.object(artifact_gate,'require_approved_identity'),patch.object(r.subprocess,'check_output') as cmd:
            with self.assertRaisesRegex(c.Blocked,'UNREVIEWED'):r.preflight(self.env())
            cmd.assert_not_called()

    def test_exact_single_parent_no_merge_or_different_delivery(self):
        for actual,parents in [('a'*40,r.CHECKOUT_PARENT),('b'*40,r.CHECKOUT_PARENT),('a'*40,'b'*40),('a'*40,r.CHECKOUT_PARENT+' '+'c'*40)]:
            with patch.object(artifact_gate,'require_approved_identity'),patch.object(c,'require_ui_review'),patch.object(r.subprocess,'check_output',side_effect=[actual,parents]):
                if actual=='a'*40 and parents==r.CHECKOUT_PARENT:self.assertTrue(r.preflight(self.env()))
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
                if args==['cmd','media_session','help']:return '--stream STREAM AudioManager.STREAM_MUSIC --set INDEX --get'
                if args==['cmd','media_session','volume','--stream','3','--get']:return '[V] volume is 0 in range [0..15]'
                if args==['cmd','media_session','volume','--stream','3','--set','0']:trace.append('set-zero');return ''
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


class CommandDiagnostics(unittest.TestCase):
    def make_run(self, root):
        obj=r.CaptureRun(root,root)
        obj.adb='/private/fixture/adb'
        obj.server=Mock(poll=Mock(return_value=None));obj.emulator=Mock(poll=Mock(return_value=None))
        return obj

    def test_benign_nonzero_and_stderr_remain_failures(self):
        for code,out,err,expected in [(1,'benign usage text','','nonzero_exit'),
                                      (0,'valid-looking result','benign warning','unexpected_stderr')]:
            with tempfile.TemporaryDirectory() as td:
                obj=self.make_run(Path(td))
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],code,out,err)):
                    with self.assertRaisesRegex(c.Blocked,'^SCOPED_ADB_COMMAND_FAILED$'):
                        obj.shell(['cmd','media_session','help'])
                self.assertEqual(obj.result['command_failure'],{
                    'command_label':'media_session_help','exit_code':code,
                    'stdout_bytes':len(out.encode()),'stderr_bytes':len(err.encode()),'classification':expected})
                self.assertFalse(obj.result['runtime_pass'])

    def test_recognized_failure_classification_has_no_raw_text(self):
        cases=[(127,'','/system/bin/sh: media: inaccessible or not found','command_missing'),
               (1,'Permission Denial: caller 999','','permission_denied'),
               (0,'','java.lang.SecurityException: hidden','permission_denied'),
               (2,'Unknown command: private-command','','unknown_command'),
               (2,'','unrecognized option: private-value','unknown_command')]
        for code,out,err,expected in cases:
            with self.subTest(expected=expected):
                result=r.safe_command_failure(['shell','cmd','media_session','help'],subprocess.CompletedProcess([],code,out,err))
                self.assertEqual(result['classification'],expected)
                serialized=json.dumps(result)
                self.assertNotIn('private-command',serialized);self.assertNotIn('private-value',serialized)
                self.assertNotIn('/system/bin/sh',serialized);self.assertNotIn('caller 999',serialized)

    def test_malicious_guest_output_and_arguments_cannot_escape_redaction(self):
        malicious='private@example.invalid /home/private/keys token=abc \n"runtime_pass":true \x1b[31m'
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            response=subprocess.CompletedProcess([],13,malicious+'☃',malicious)
            with patch.object(obj,'command',return_value=response):
                with self.assertRaises(c.Blocked):obj.adb_command(['unrecognized',malicious])
            serialized=json.dumps(obj.result)
            for token in ('private@example.invalid','/home/private/keys','token=abc','31m','/private/fixture/adb'):
                self.assertNotIn(token,serialized)
            self.assertEqual(obj.result['command_failure']['command_label'],'unrecognized_command')
            self.assertEqual(obj.result['command_failure']['stdout_bytes'],len((malicious+'☃').encode()))
            self.assertFalse(obj.result['runtime_pass'])

    def test_command_labels_are_fixed_bounded_allowlist(self):
        self.assertTrue(all(1<=len(label)<=32 and label.isascii() and label.replace('_','').isalpha()
                            for label in r.ADB_COMMAND_LABELS))
        for command,label in r._ADB_FIXED_LABELS.items():self.assertEqual(r.safe_command_label(list(command)),label)
        for command in ([],['secret'*1000],[None],{'command':'secret'},'raw secret',
                        ['shell','cmd','media_session','volume','--set','999']):
            self.assertEqual(r.safe_command_label(command),'unrecognized_command')
        for command in (['install','--user','0','/private/secret.apk'],['exec-out','cat','/private/secret'],
                        ['shell','input','tap','123','456']):
            label=r.safe_command_label(command)
            self.assertIn(label,r.ADB_COMMAND_LABELS);self.assertNotIn('private',label)
        self.assertEqual(r.ADB_FAILURE_CLASSES,{'command_missing','permission_denied','unknown_command','unexpected_stderr','nonzero_exit'})

    def test_success_and_existing_explicit_allowed_code_behavior_unchanged(self):
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            for code,allowed in [(0,(0,)),(1,(0,1))]:
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],code,'  result\r\n','  ')):
                    self.assertEqual(obj.adb_command(['unknown'],allowed_codes=allowed),'result')
                self.assertNotIn('command_failure',obj.result)

    def test_successful_fixed_readback_metadata_is_bounded_and_redacted(self):
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td));private='private@example.invalid☃'
            with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],0,private,'')):
                for _ in range(r.COMMAND_DIAGNOSTIC_LIMIT+5):obj.shell(['getprop','persist.sys.locale'])
                obj.adb_command(['arbitrary','private-argument'])
            trace=obj.result['command_diagnostics']
            self.assertEqual(len(trace),r.COMMAND_DIAGNOSTIC_LIMIT)
            self.assertEqual(obj.result['command_diagnostics_dropped'],5)
            self.assertTrue(all(item=={'command_label':'read_locale','exit_code':0,
                                      'stdout_bytes':len(private.encode()),'stderr_bytes':0} for item in trace))
            self.assertNotIn(private,json.dumps(obj.result));self.assertNotIn('private-argument',json.dumps(obj.result))
            self.assertNotIn('command_failure',obj.result)

    def test_idle_readback_stages_identify_read_and_validation_boundaries(self):
        from test_runtime_contract import audio_fixture,policy_fixture,sessions_fixture
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td));obj.stage='guest-precondition-idle';observed=[]
            values={('cmd','media_session','volume','--stream','3','--get'):'[V] volume is 0 in range [0..15]',
                    ('dumpsys','media_projection'):'MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull',
                    ('dumpsys','audio'):audio_fixture(),('dumpsys','media.audio_policy'):policy_fixture(),
                    ('dumpsys','media_session'):sessions_fixture()}
            def shell(args,**kw):observed.append(obj.stage);return values[tuple(args)]
            with patch.object(obj,'shell',side_effect=shell):obj.assert_idle()
            self.assertEqual(observed,['idle-volume-readback','idle-projection-readback','idle-audio-readback',
                                       'idle-audio-policy-readback','idle-media-session-readback'])
            self.assertEqual(obj.stage,'guest-precondition-idle')
            with patch.object(obj,'shell',side_effect=shell),patch.object(c,'verify_audio_idle',side_effect=c.Blocked('UNKNOWN_OR_AMBIGUOUS_CURRENT_STATE')):
                with self.assertRaises(c.Blocked):obj.assert_idle()
            self.assertEqual(obj.stage,'idle-audio-policy-session-validation')

    def test_first_failure_is_preserved(self):
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            for cmd,code in [(['shell','cmd','media_session','help'],127),(['uninstall',c.PACKAGE],3)]:
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],code,'','')):
                    with self.assertRaises(c.Blocked):obj.adb_command(cmd)
            self.assertEqual(obj.result['command_failure']['command_label'],'media_session_help')
            self.assertEqual(obj.result['command_failure']['exit_code'],127)

    def test_precise_precondition_failure_stages_before_install(self):
        from test_runtime_contract import audio_fixture,policy_fixture,sessions_fixture
        outputs={
            ('getprop','persist.sys.locale'):'',('getprop','ro.product.locale'):'en-US',
            ('wm','size'):'Physical size: 480x800',('pm','list','packages',c.PACKAGE):'',
            ('cmd','media_session','help'):'--stream STREAM AudioManager.STREAM_MUSIC --set INDEX --get',
            ('cmd','media_session','volume','--stream','3','--get'):'[V] volume is 0 in range [0..15]',
            ('cmd','media_session','volume','--stream','3','--set','0'):'',
            ('dumpsys','media_projection'):'MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull',
            ('dumpsys','audio'):audio_fixture(),('dumpsys','media.audio_policy'):policy_fixture(),
            ('dumpsys','media_session'):sessions_fixture()}
        cases=[(('getprop','persist.sys.locale'),'guest-precondition-locale'),
               (('getprop','ro.product.locale'),'guest-precondition-fallback-locale'),
               (('wm','size'),'guest-precondition-display'),
               (('pm','list','packages',c.PACKAGE),'guest-precondition-package-absence'),
               (('cmd','media_session','help'),'guest-precondition-volume-help'),
               (('cmd','media_session','volume','--stream','3','--get'),'guest-precondition-volume-read'),
               (('cmd','media_session','volume','--stream','3','--set','0'),'guest-precondition-volume-zero'),
               (('dumpsys','media_projection'),'idle-projection-readback'),
               (('dumpsys','audio'),'idle-audio-readback'),
               (('dumpsys','media.audio_policy'),'idle-audio-policy-readback'),
               (('dumpsys','media_session'),'idle-media-session-readback')]
        for failed,stage in cases:
            with self.subTest(failed=failed),tempfile.TemporaryDirectory() as td:
                obj=self.make_run(Path(td));artifact=Mock();calls=[]
                def command(argv,**kw):
                    self.assertEqual(argv[:6],[obj.adb,'-P','5038','-s','emulator-5556','shell'])
                    args=tuple(argv[6:]);calls.append(args)
                    return subprocess.CompletedProcess([],2,'', 'unknown command: redacted') if args==failed else subprocess.CompletedProcess([],0,outputs[args],'')
                with patch.object(b.Probe,'execute'),patch.object(obj,'command',side_effect=command):
                    with self.assertRaises(c.Blocked):obj.prepare_guest(artifact)
                self.assertEqual(obj.stage,stage);self.assertFalse(obj.installed)
                self.assertEqual(obj.result['command_failure']['command_label'],r.safe_command_label(['shell',*failed]))
                self.assertFalse(obj.result['runtime_pass']);artifact.recheck.assert_not_called()

    def test_checkout_revision_parent_does_not_retarget_signed_delivery(self):
        self.assertEqual(r.CHECKOUT_PARENT,'2c795b70f3cd32b458d9e7346a2f039c7ce04d8a')
        self.assertEqual(b.PROBE_PARENT,'4062451c315265c827161f7e3ac925ff8df39121')
        self.assertEqual(artifact_gate.DELIVERY_PARENT,b.PROBE_PARENT)
        with patch.object(artifact_gate,'require_approved_identity') as identity,patch.object(c,'require_ui_review'),patch.object(r.subprocess,'check_output',side_effect=['a'*40,r.CHECKOUT_PARENT]):
            r.preflight(Runtime().env())
        identity.assert_called_once_with(b.PROBE_PARENT)

    def test_modern_media_service_cli_and_only_zero_write(self):
        source=Path(r.__file__).read_text()
        self.assertNotIn("['media',",source)
        self.assertIn("['cmd', 'media_session', 'help']",source)
        self.assertIn("['cmd', 'media_session', 'volume', '--stream', '3', '--get']",source)
        self.assertIn("['cmd', 'media_session', 'volume', '--stream', '3', '--set', '0']",source)
        self.assertNotIn("'--adj'",source)
        self.assertIsNone(c.RECORD_AUDIO_UI);self.assertIsNone(c.PROJECTION_UI_STEPS);self.assertIsNone(c.ROOT_RUNTIME_REVIEW)


if __name__=='__main__':unittest.main(verbosity=2)
