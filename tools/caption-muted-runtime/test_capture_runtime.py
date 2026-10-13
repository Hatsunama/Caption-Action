"""Control-flow and static invariants without SDK, real ADB, guest, or signed bytes."""
from pathlib import Path
import tempfile
import json
import subprocess
import unittest
from test_runtime_contract import (good_metrics, package_fixture, MEDIA_HELP_STDOUT,
                                   VOLUME_SET_ZERO_STDOUT, volume_get_stdout, AUDIO_HELP_STDOUT, AUDIO_SET_ZERO_STDOUT)
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
                with self.assertRaises(c.Blocked):obj.adb_command(['shell','cmd','media_session'],allowed_codes=(255,))
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
                if args==['cmd','audio']:return AUDIO_HELP_STDOUT
                if args==['cmd','audio','get-min-volume','3']:return 'AudioManager.getStreamMinVolume(3) -> 0'
                if args==['cmd','audio','get-max-volume','3']:return 'AudioManager.getStreamMaxVolume(3) -> 15'
                if args==['cmd','audio','get-stream-volume','3']:return 'AudioManager.getStreamVolume(3) -> 0'
                if args==['cmd','audio','set-volume','3','0']:trace.append('set-zero');return AUDIO_SET_ZERO_STDOUT
                if args==['cmd','media_session','volume','--stream','3','--get']:return volume_get_stdout()
                if args==['cmd','media_session','volume','--stream','3','--set','0']:trace.append('set-zero');return VOLUME_SET_ZERO_STDOUT
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
                        obj.shell(['cmd','media_session','volume','--stream','3','--get'])
                self.assertEqual(obj.result['command_failure'],{
                    'command_label':'media_session_volume_read','exit_code':code,
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
                result=r.safe_command_failure(['shell','cmd','media_session'],subprocess.CompletedProcess([],code,out,err))
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
            values={('cmd','media_session','volume','--stream','3','--get'):volume_get_stdout(),
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
            for cmd,code in [(['shell','cmd','media_session'],127),(['uninstall',c.PACKAGE],3)]:
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],code,'','')):
                    with self.assertRaises(c.Blocked):obj.adb_command(cmd,allowed_codes=(255,) if cmd==['shell','cmd','media_session'] else (0,))
            self.assertEqual(obj.result['command_failure']['command_label'],'media_session_help')
            self.assertEqual(obj.result['command_failure']['exit_code'],127)

    def test_precise_precondition_failure_stages_before_install(self):
        from test_runtime_contract import audio_fixture,policy_fixture,sessions_fixture
        outputs={
            ('getprop','persist.sys.locale'):'',('getprop','ro.product.locale'):'en-US',
            ('wm','size'):'Physical size: 480x800',('pm','list','packages',c.PACKAGE):'',
            ('cmd','audio'):AUDIO_HELP_STDOUT,
            ('cmd','audio','get-min-volume','3'):'AudioManager.getStreamMinVolume(3) -> 0\n',
            ('cmd','audio','get-max-volume','3'):'AudioManager.getStreamMaxVolume(3) -> 15\n',
            ('cmd','audio','get-stream-volume','3'):'AudioManager.getStreamVolume(3) -> 0\n',
            ('cmd','audio','set-volume','3','0'):AUDIO_SET_ZERO_STDOUT,
            ('cmd','media_session','volume','--stream','3','--get'):volume_get_stdout(),
            ('cmd','media_session','volume','--stream','3','--set','0'):VOLUME_SET_ZERO_STDOUT,
            ('dumpsys','media_projection'):'MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull',
            ('dumpsys','audio'):audio_fixture(),('dumpsys','media.audio_policy'):policy_fixture(),
            ('dumpsys','media_session'):sessions_fixture()}
        cases=[(('getprop','persist.sys.locale'),'guest-precondition-locale'),
               (('getprop','ro.product.locale'),'guest-precondition-fallback-locale'),
               (('wm','size'),'guest-precondition-display'),
               (('pm','list','packages',c.PACKAGE),'guest-precondition-package-absence'),
               (('cmd','audio'),'audio-diagnostic-help'),
               (('cmd','media_session','volume','--stream','3','--get'),'audio-diagnostic-before-media'),
               (('cmd','audio','set-volume','3','0'),'audio-diagnostic-single-zero'),
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
                    return subprocess.CompletedProcess([],2,'', 'unknown command: redacted') if args==failed else subprocess.CompletedProcess([],255 if args==('cmd','audio') else 0,outputs[args],'')
                with patch.object(b.Probe,'execute'),patch.object(obj,'command',side_effect=command):
                    with self.assertRaises(c.Blocked):obj.prepare_guest(artifact)
                self.assertEqual(obj.stage,stage);self.assertFalse(obj.installed)
                self.assertEqual(obj.result['command_failure']['command_label'],r.safe_command_label(['shell',*failed]))
                self.assertFalse(obj.result['runtime_pass']);artifact.recheck.assert_not_called()

    def test_checkout_revision_parent_does_not_retarget_signed_delivery(self):
        self.assertEqual(r.CHECKOUT_PARENT,'3473e5d405c680e0e71a05086ace8e52a8db1431')
        self.assertEqual(b.PROBE_PARENT,'4062451c315265c827161f7e3ac925ff8df39121')
        self.assertEqual(artifact_gate.DELIVERY_PARENT,b.PROBE_PARENT)
        with patch.object(artifact_gate,'require_approved_identity') as identity,patch.object(c,'require_ui_review'),patch.object(r.subprocess,'check_output',side_effect=['a'*40,r.CHECKOUT_PARENT]):
            r.preflight(Runtime().env())
        identity.assert_called_once_with(b.PROBE_PARENT)

    def test_modern_media_service_cli_and_only_zero_write(self):
        source=Path(r.__file__).read_text()
        self.assertNotIn("['media',",source)
        self.assertIn("['cmd', 'audio'], allowed_codes=(255,)",source)
        self.assertIn("['cmd', 'media_session', 'volume', '--stream', '3', '--get']",source)
        self.assertIn("self.shell(['cmd', 'audio', 'set-volume', '3', '0'])",source)
        self.assertNotIn("self.shell(['cmd', 'media_session', 'volume', '--stream', '3', '--set'",source)
        self.assertIn('LEGACY_VOLUME_SET_DISABLED',source)
        self.assertNotIn("'--adj'",source)
        self.assertIsNone(c.RECORD_AUDIO_UI);self.assertIsNone(c.PROJECTION_UI_STEPS);self.assertIsNone(c.ROOT_RUNTIME_REVIEW)


class Android16MediaFixture:
    """Source-connected fake only; returns both channels and Android shell status.

    MediaShellCommand.onCommand returns-1 for missing/literal/unknown commands;
    shell observes255. VolumeCtrl invalid set calls showError and returns normally,
    so onCommand returns0 despite help on stdout and an error on stderr.
    """
    def __init__(self, initial=7, setter_works=True):
        self.volume=initial;self.setter_works=setter_works;self.media_calls=[]

    def response(self, args):
        args=tuple(args);self.media_calls.append(args)
        if args==('cmd','audio'):
            return subprocess.CompletedProcess([],255,AUDIO_HELP_STDOUT,'')
        for command,method,value in [('get-min-volume','getStreamMinVolume',0),('get-max-volume','getStreamMaxVolume',15),('get-stream-volume','getStreamVolume',self.volume)]:
            if args==('cmd','audio',command,'3'):
                return subprocess.CompletedProcess([],0,f'AudioManager.{method}(3) -> {value}\n','')
        if args==('cmd','audio','set-volume','3','0'):
            if self.setter_works:self.volume=0
            return subprocess.CompletedProcess([],0,AUDIO_SET_ZERO_STDOUT,'')
        if args==('cmd','media_session'):
            return subprocess.CompletedProcess([],255,MEDIA_HELP_STDOUT,'')
        if args==('cmd','media_session','help'):
            return subprocess.CompletedProcess([],255,MEDIA_HELP_STDOUT,"Error: unknown command 'help'\n")
        if args==('cmd','media_session','volume','--stream','3','--get'):
            return subprocess.CompletedProcess([],0,volume_get_stdout(self.volume),'')
        if args==('cmd','media_session','volume','--stream','3','--set','0'):
            if self.setter_works:self.volume=0
            return subprocess.CompletedProcess([],0,VOLUME_SET_ZERO_STDOUT,'')
        if args==('cmd','media_session','volume','--stream','3','--set','16'):
            out=VOLUME_SET_ZERO_STDOUT.replace('index=0','index=16')+MEDIA_HELP_STDOUT
            err='Error: invalid volume index 16 for stream 3 (should be in [0..15])\n'
            return subprocess.CompletedProcess([],0,out,err)
        if args==('cmd','media_session','volume','--stream','3','--bogus'):
            return subprocess.CompletedProcess([],255,'[V] will control stream=3 (STREAM_MUSIC)\n'+MEDIA_HELP_STDOUT,
                'java.lang.IllegalArgumentException: Unknown argument --bogus\n')
        raise AssertionError('Unknown test fixture command')


class MediaCommandContract(unittest.TestCase):
    def make_run(self, root):
        return CommandDiagnostics().make_run(root)

    def test_source_fixture_byte_counts_and_channels(self):
        fixture=Android16MediaFixture()
        bare=fixture.response(['cmd','media_session'])
        bad=fixture.response(['cmd','media_session','help'])
        self.assertEqual((bare.returncode,len(bare.stdout.encode()),len(bare.stderr.encode())),(255,1366,0))
        self.assertEqual((bad.returncode,len(bad.stdout.encode()),len(bad.stderr.encode())),(255,1366,30))
        get=fixture.response(['cmd','media_session','volume','--stream','3','--get'])
        self.assertEqual(get.stdout.splitlines(),['[V] will control stream=3 (STREAM_MUSIC)',
            '[V] will get volume','[V] Connecting to AudioService','[V] volume is 7 in range [0..15]'])
        zero=fixture.response(['cmd','media_session','volume','--stream','3','--set','0'])
        self.assertEqual(zero.stdout.splitlines(),['[V] will control stream=3 (STREAM_MUSIC)',
            '[V] will set volume to index=0','[V] Connecting to AudioService'])
        self.assertEqual((get.returncode,get.stderr,zero.returncode,zero.stderr),(0,'',0,''))

    def connected(self, setter_works=True):
        from test_runtime_contract import audio_fixture,policy_fixture,sessions_fixture
        class CheckedBeforeInstall(Exception):pass
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td));fixture=Android16MediaFixture(setter_works=setter_works)
            outputs={('getprop','persist.sys.locale'):'en-US',('wm','size'):'Physical size: 480x800',
                ('pm','list','packages',c.PACKAGE):'',
                ('dumpsys','media_projection'):'MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull',
                ('dumpsys','audio'):'Audio service state\n'+audio_fixture(),('dumpsys','media.audio_policy'):'AudioPolicyManager state\n'+policy_fixture(),
                ('dumpsys','media_session'):sessions_fixture()}
            artifact=Mock();artifact.recheck.side_effect=CheckedBeforeInstall
            def command(argv,**kwargs):
                self.assertEqual(argv[:6],[obj.adb,'-P','5038','-s','emulator-5556','shell'])
                args=tuple(argv[6:])
                if args[:1]==('cmd',):return fixture.response(args)
                return subprocess.CompletedProcess([],0,outputs[args],'')
            with patch.object(b.Probe,'execute'),patch.object(obj,'command',side_effect=command):
                if setter_works:
                    with self.assertRaises(CheckedBeforeInstall):obj.prepare_guest(artifact)
                else:
                    with self.assertRaisesRegex(c.Blocked,'MEDIA_VOLUME_NOT_ZERO'):obj.prepare_guest(artifact)
            self.assertFalse(obj.installed);self.assertFalse(obj.result['runtime_pass'])
            expected=[command[1:] for command in r.AUDIO_DIAGNOSTIC_COMMANDS]
            if setter_works:expected.append(('cmd','media_session','volume','--stream','3','--get'))
            self.assertEqual(fixture.media_calls,expected)
            self.assertEqual(obj.result['volume_write_attempts'],1)
            return obj

    def test_connected_source_help_get_set_and_zero_readback(self):
        self.connected()

    def test_connected_set_without_effect_never_reaches_install(self):
        obj=self.connected(setter_works=False)
        self.assertEqual(obj.stage,'audio-diagnostic-after-media')

    def test_shared_helper_releases_only_after_dual_zero_and_never_reruns(self):
        for works in (False,True):
            obj=self.connected(setter_works=works)
            self.assertTrue(obj.mute_setup_started);self.assertEqual(obj.volume_write_attempts,1)
            self.assertEqual(obj.audio_diagnostic_index,None if works else 8)
            with patch.object(obj,'command') as command:
                with self.assertRaisesRegex(c.Blocked,'MUTE_SETUP_ALREADY_STARTED'):obj.verified_mute_setup()
                with self.assertRaisesRegex(c.Blocked,'MUTE_SETUP_ALREADY_STARTED'):obj.prepare_guest(Mock())
                with self.assertRaises(c.Blocked):obj.shell(['cmd','audio','set-volume','3','0'])
                with self.assertRaises(c.Blocked):obj.shell(['cmd','media_session','volume','--stream','3','--set','0'])
                command.assert_not_called()
            with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],0,'Success','')) as command:
                if works:self.assertEqual(obj.adb_command(['install','--user','0','fixture.apk']),'Success')
                else:
                    with self.assertRaises(c.Blocked):obj.adb_command(['install','--user','0','fixture.apk'])
                    command.assert_not_called()

    def test_global_write_attempt_budget_survives_local_guard_or_result_reset(self):
        obj=self.connected()
        obj.audio_diagnostic_index=5
        obj.result['volume_write_attempts']=0  # Presentation fields cannot reset the budget.
        with patch.object(obj,'command') as command,self.assertRaisesRegex(c.Blocked,'VOLUME_WRITE_ALREADY_ATTEMPTED'):
            obj.shell(['cmd','audio','set-volume','3','0'])
        command.assert_not_called();self.assertEqual(obj.volume_write_attempts,1)

    def test_discovery_preflight_requires_verified_audio_setup_review(self):
        with patch.object(c,'AUDIO_DIAGNOSTIC_REVIEW',None),patch.object(artifact_gate,'require_approved_identity'),patch.object(r.subprocess,'check_output') as git:
            with self.assertRaisesRegex(c.Blocked,'UNREVIEWED_AUDIO_DIAGNOSTIC'):r.preflight(Runtime().env(),'discover-record')
            git.assert_not_called()

    def test_bare_help_is_only_255_exception(self):
        fixture=Android16MediaFixture()
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            with patch.object(obj,'command',return_value=fixture.response(['cmd','media_session'])):
                text=obj.shell(['cmd','media_session'],allowed_codes=(255,));c.verify_volume_help(text)
            for args in (['cmd','media_session','help'],['cmd','media_session','volume','--stream','3','--get']):
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],255,MEDIA_HELP_STDOUT,'')):
                    with self.assertRaises(c.Blocked):obj.shell(args,allowed_codes=(255,))
            with patch.object(obj,'command',return_value=fixture.response(['cmd','media_session'])):
                with self.assertRaises(c.Blocked):obj.shell(['cmd','media_session'],allowed_codes=(0,255))

    def test_help_errors_and_invalid_set_zero_exit_are_rejected(self):
        fixture=Android16MediaFixture()
        cases=[(['cmd','media_session','help'],(0,)),
               (['cmd','media_session','volume','--stream','3','--set','16'],(0,)),
               (['cmd','media_session','volume','--stream','3','--bogus'],(0,))]
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            for args,codes in cases:
                result=fixture.response(args)
                with patch.object(obj,'command',return_value=result):
                    with self.assertRaises(c.Blocked):obj.shell(args,allowed_codes=codes)
                self.assertFalse(obj.result['runtime_pass'])
            invalid=fixture.response(cases[1][0])
            self.assertEqual(invalid.returncode,0);self.assertTrue(invalid.stdout.endswith(MEDIA_HELP_STDOUT));self.assertTrue(invalid.stderr)

    def test_bare_help_requires_exact_header_options_and_empty_stderr(self):
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            for output,err in [(MEDIA_HELP_STDOUT,' '),(MEDIA_HELP_STDOUT,'\n'),
                               (MEDIA_HELP_STDOUT.replace('usage: media_session','usage: other'),''),
                               (MEDIA_HELP_STDOUT.replace('--stream STREAM','--stream UNKNOWN'),''),
                               ('private-prefix\n'+MEDIA_HELP_STDOUT,''),(MEDIA_HELP_STDOUT+MEDIA_HELP_STDOUT,'')]:
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],255,output,err)):
                    with self.assertRaises(c.Blocked):obj.shell(['cmd','media_session'],allowed_codes=(255,))

    def test_volume_commands_require_exit_zero_and_empty_stderr(self):
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            for args,output in [(['cmd','media_session','volume','--stream','3','--get'],volume_get_stdout()),
                                (['cmd','media_session','volume','--stream','3','--set','0'],VOLUME_SET_ZERO_STDOUT)]:
                for stderr in (' ', '\n', 'Error: invalid volume'):
                    with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],0,output,stderr)):
                        with self.assertRaises(c.Blocked):obj.shell(args)
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],1,output,'')) as command:
                    with self.assertRaises(c.Blocked):obj.shell(args,allowed_codes=(1,))
                    command.assert_not_called()

    def test_volume_responses_require_complete_ordered_source_stdout(self):
        with tempfile.TemporaryDirectory() as td:
            obj=self.make_run(Path(td))
            get=['cmd','media_session','volume','--stream','3','--get']
            zero=['cmd','media_session','volume','--stream','3','--set','0']
            cases=[(get,'[V] volume is 0 in range [0..15]\n'),
                   (get,volume_get_stdout().replace('stream=3 (STREAM_MUSIC)','stream=0 (STREAM_VOICE_CALL)')),
                   (get,volume_get_stdout().replace('[V] will get volume\n','')),
                   (get,'\n'.join(reversed(volume_get_stdout().splitlines()))+'\n'),
                   (get,'unrelated\n'+volume_get_stdout()),
                   (zero,''),(zero,VOLUME_SET_ZERO_STDOUT.replace('index=0','index=1')),
                   (zero,VOLUME_SET_ZERO_STDOUT+MEDIA_HELP_STDOUT)]
            for args,output in cases:
                with patch.object(obj,'command',return_value=subprocess.CompletedProcess([],0,output,'')):
                    with self.assertRaises(c.Blocked):obj.shell(args)



class AudioDiagnostic(unittest.TestCase):
    # Full source-backed output fixture; no SDK/ADB/emulator is executed.
    def run_fixture(self, *, minimum=0, maximum=15, initial=7, after=0, media_after=None, fault=None):
        td=tempfile.TemporaryDirectory();self.addCleanup(td.cleanup)
        obj=CommandDiagnostics().make_run(Path(td.name));trace=[]
        replies=[(255,AUDIO_HELP_STDOUT,''),
                 (0,f'AudioManager.getStreamMinVolume(3) -> {minimum}\n',''),
                 (0,f'AudioManager.getStreamMaxVolume(3) -> {maximum}\n',''),
                 (0,f'AudioManager.getStreamVolume(3) -> {initial}\n',''),
                 (0,volume_get_stdout(initial,minimum,maximum),''),
                 (0,AUDIO_SET_ZERO_STDOUT,''),
                 (0,f'AudioManager.getStreamVolume(3) -> {after}\n',''),
                 (0,volume_get_stdout(after if media_after is None else media_after,minimum,maximum),'')]
        if fault is not None:replies[fault[0]]=fault[1]
        def command(argv,**kw):
            trace.append(tuple(argv[6:]));index=len(trace)-1
            return subprocess.CompletedProcess([], *replies[index])
        error=None
        review={'mode':'diagnose-volume','parent':b.PROBE_PARENT,'scope':'single-zero-no-install-ui-capture'}
        with patch.object(b.Probe,'execute'),patch.object(c,'AUDIO_DIAGNOSTIC_REVIEW',review),patch.object(obj,'command',side_effect=command),patch.object(obj,'prepare_guest',side_effect=AssertionError('install path')):
            try:obj.execute_audio_diagnostic()
            except c.Blocked as e:error=str(e)
        return obj,trace,error

    def test_connected_single_write_both_readbacks_then_stop(self):
        obj,trace,error=self.run_fixture()
        self.assertIsNone(error)
        self.assertEqual(trace,[('cmd','audio'),('cmd','audio','get-min-volume','3'),
            ('cmd','audio','get-max-volume','3'),('cmd','audio','get-stream-volume','3'),
            ('cmd','media_session','volume','--stream','3','--get'),('cmd','audio','set-volume','3','0'),
            ('cmd','audio','get-stream-volume','3'),('cmd','media_session','volume','--stream','3','--get')])
        self.assertEqual(obj.result['volume_before'],{'audio':{'value':7,'min':0,'max':15},'media_session':{'value':7,'min':0,'max':15}})
        self.assertEqual(obj.result['volume_after']['audio']['value'],0)
        self.assertEqual(obj.result['volume_after']['media_session']['value'],0)
        self.assertEqual(obj.result['volume_write_attempts'],1)
        self.assertEqual(obj.result['status'],'audio-diagnostic-pending-host-cleanup')
        self.assertFalse(obj.result['runtime_pass']);self.assertFalse(obj.installed)
        self.assertFalse(obj.result['capture_started']);self.assertFalse(obj.result['permissions_granted'])
        with patch.object(obj,'command') as command,self.assertRaises(c.Blocked):
            obj.shell(['cmd','audio','set-volume','3','0'])
        command.assert_not_called()

    def test_bad_support_range_and_prewrite_errors_never_write(self):
        for params in ({'minimum':1},{'maximum':0},{'initial':16},
                       {'fault':(0,(0,'',''))},{'fault':(0,(255,AUDIO_HELP_STDOUT,' '))},
                       {'fault':(1,(0,'AudioManager.getStreamMinVolume(3) -> 0\nprivate token',''))},
                       {'fault':(3,(1,'','Permission Denial private@example.invalid'))}):
            with self.subTest(params=params):
                obj,trace,error=self.run_fixture(**params)
                self.assertIsNotNone(error);self.assertEqual(obj.result['volume_write_attempts'],0)
                self.assertFalse(any('set-volume' in command for command in trace))
                self.assertFalse(obj.installed);self.assertNotIn('private',json.dumps(obj.result))

    def test_nonzero_after_reads_both_then_stops_without_retry(self):
        for a,m in [(7,7),(0,7),(7,0)]:
            obj,trace,error=self.run_fixture(after=a,media_after=m)
            self.assertEqual(error,'MEDIA_VOLUME_NOT_ZERO');self.assertEqual(len(trace),8)
            self.assertEqual(obj.result['volume_write_attempts'],1)
            self.assertEqual(obj.result['volume_after']['audio']['value'],a)
            self.assertEqual(obj.result['volume_after']['media_session']['value'],m)
            self.assertFalse(obj.result['runtime_pass']);self.assertFalse(obj.installed)

    def test_set_error_or_malformed_return_never_retries(self):
        private='secret-token /private/path private@example.invalid'
        for response in [(1,'',private),(0,AUDIO_SET_ZERO_STDOUT,private),(0,'', ''),(0,AUDIO_SET_ZERO_STDOUT+private,'')]:
            obj,trace,error=self.run_fixture(fault=(5,response))
            self.assertIsNotNone(error);self.assertEqual(len(trace),6)
            self.assertEqual(obj.result['volume_write_attempts'],1)
            self.assertNotIn(private,json.dumps(obj.result));self.assertFalse(obj.installed)

    def test_every_command_failure_stops_before_next_command(self):
        for index in range(8):
            obj,trace,error=self.run_fixture(fault=(index,(9,'private-stdout','private-stderr')))
            self.assertIsNotNone(error);self.assertEqual(len(trace),index+1)
            self.assertLessEqual(obj.result['volume_write_attempts'],1)
            self.assertFalse(obj.installed);self.assertNotIn('private-',json.dumps(obj.result))

    def test_diagnostic_gate_none_cannot_boot_or_enable_capture(self):
        td=tempfile.TemporaryDirectory();self.addCleanup(td.cleanup)
        obj=CommandDiagnostics().make_run(Path(td.name))
        with patch.object(c,'AUDIO_DIAGNOSTIC_REVIEW',None):
            with patch.object(b.Probe,'execute') as boot,self.assertRaisesRegex(c.Blocked,'UNREVIEWED_AUDIO_DIAGNOSTIC'):
                obj.execute_audio_diagnostic()
            boot.assert_not_called()
            with patch.object(artifact_gate,'require_approved_identity'),patch.object(r.subprocess,'check_output') as git,self.assertRaises(c.Blocked):
                r.preflight(Runtime().env(),'diagnose-volume')
            git.assert_not_called()
        self.assertIsNone(c.ROOT_RUNTIME_REVIEW)

    def test_diagnostic_gate_is_exact_mode_parent_and_scope(self):
        approved={'mode':'diagnose-volume','parent':b.PROBE_PARENT,'scope':'single-zero-no-install-ui-capture'}
        with patch.object(c,'AUDIO_DIAGNOSTIC_REVIEW',approved):
            c.require_audio_diagnostic_review(b.PROBE_PARENT)
            with self.assertRaises(c.Blocked):c.require_audio_diagnostic_review('a'*40)
        for key,value in [('mode','capture'),('scope','anything')]:
            with patch.object(c,'AUDIO_DIAGNOSTIC_REVIEW',{**approved,key:value}),self.assertRaises(c.Blocked):
                c.require_audio_diagnostic_review(b.PROBE_PARENT)

    def test_no_out_of_sequence_or_nonzero_mutation_can_dispatch(self):
        td=tempfile.TemporaryDirectory();self.addCleanup(td.cleanup)
        for args in (['install','--user','0','private.apk'],['shell','input','tap','1','1'],
                     ['shell','cmd','audio','set-volume','3','1'],
                     ['shell','cmd','media_session','volume','--stream','3','--set','0'],
                     ['shell','cmd','audio','set-volume','3','0']):
            obj=CommandDiagnostics().make_run(Path(td.name));obj.audio_diagnostic_index=0
            with patch.object(obj,'command') as command,self.assertRaises(c.Blocked):obj.adb_command(args)
            command.assert_not_called()

    def test_timeout_during_single_set_consumes_attempt_and_cannot_retry(self):
        td=tempfile.TemporaryDirectory();self.addCleanup(td.cleanup)
        obj=CommandDiagnostics().make_run(Path(td.name));obj.audio_diagnostic_index=5
        obj.result['volume_write_attempts']=0
        with patch.object(obj,'command',side_effect=c.Blocked('COMMAND_TIMEOUT_OR_OUTPUT_LIMIT')) as command:
            with self.assertRaises(c.Blocked):obj.shell(['cmd','audio','set-volume','3','0'])
            with self.assertRaisesRegex(c.Blocked,'COMMAND_ORDER'):obj.shell(['cmd','audio','set-volume','3','0'])
        self.assertEqual(command.call_count,1);self.assertEqual(obj.result['volume_write_attempts'],1)
        self.assertEqual(obj.result['last_command_label'],'audio_volume_zero')

    def test_host_cleanup_required_and_failure_cannot_pass(self):
        for failed in (False,True):
            obj,_,error=self.run_fixture();self.assertIsNone(error)
            obj.emulator=None;obj.server=None
            def cleanup():obj.result['cleanup_errors']=[];return not failed
            with patch.object(b.Probe,'cleanup',side_effect=cleanup):obj.cleanup()
            self.assertEqual(obj.result['status'],'cleanup-failed' if failed else 'AUDIO_DIAGNOSTIC_STOPPED')
            self.assertFalse(obj.result['runtime_pass'])

    def test_inherited_cleanup_exception_text_is_redacted_even_if_it_raises(self):
        for raises in (False, True):
            obj,_,error=self.run_fixture();self.assertIsNone(error)
            obj.emulator=None;obj.server=None
            def cleanup():
                obj.result['cleanup_errors']=['private@example.invalid /private/secret token=abc']
                if raises:raise RuntimeError('private exception path')
                return False
            with patch.object(b.Probe,'cleanup',side_effect=cleanup):
                if raises:
                    with self.assertRaises(RuntimeError):obj.cleanup()
                else:self.assertFalse(obj.cleanup())
            self.assertEqual(obj.result['cleanup_errors'],['HOST_CLEANUP_UNVERIFIED'])
            self.assertNotIn('private',json.dumps(obj.result));self.assertNotIn('token=',json.dumps(obj.result))


if __name__=='__main__':unittest.main(verbosity=2)
