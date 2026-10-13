"""Verified descriptor-only install; synthetic fixtures, never an Android target."""
import hashlib,os,pathlib,subprocess,sys,tempfile,unittest
from types import SimpleNamespace
from unittest.mock import Mock
import artifact_gate as a,safety_watchdog as w
class StdinRunner(unittest.TestCase):
 def test_binary_stdin_from_retained_readonly_fd(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'fixture.apk';data=bytes(range(256));p.write_bytes(data)
   with p.open('rb') as f:
    result=w.run_bounded([sys.executable,'-c','import sys,hashlib;print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())'],timeout=2,max_output_bytes=128,stdin_fd=f.fileno(),stdin_bytes=len(data))
   self.assertEqual(result.returncode,0);self.assertEqual(result.stdout.strip(),hashlib.sha256(data).hexdigest())
 def test_invalid_stdin_contract_blocks_before_spawn(self):
  from unittest.mock import patch
  with tempfile.TemporaryFile() as f:
   f.write(b'abc');f.flush();f.seek(0)
   for fd,size in ((f.fileno(),3),(f.fileno(),2),(None,3),(123456,3),(True,3)):
    with patch.object(w.subprocess,'Popen') as spawn:
     with self.assertRaises(w.Blocked):w.run_bounded(['unused'],timeout=1,max_output_bytes=20,stdin_fd=fd,stdin_bytes=size)
     spawn.assert_not_called()
class InstallAdapter(unittest.TestCase):
 def fixture(self,root):
  p=pathlib.Path(root)/'signed.apk';data=bytes(range(256));p.write_bytes(data)
  checked=a.CheckedFile(str(p),1024)
  artifact=a.VerifiedArtifact(checked,{'signed_sha256':hashlib.sha256(data).hexdigest(),'signed_size':len(data)},[],{})
  run=SimpleNamespace(result={},adb='/fixture/adb',server=SimpleNamespace(poll=lambda:None),emulator=SimpleNamespace(poll=lambda:None),command=Mock(return_value=subprocess.CompletedProcess([],0,'Success\n','')))
  def consume(*args,**kwargs):
   os.read(kwargs['stdin_fd'],kwargs['stdin_bytes']);return run.command.return_value
  run.command.side_effect=consume
  return p,artifact,run
 def test_exact_descriptor_stream_command_and_success(self):
  import install_adapter as m
  with tempfile.TemporaryDirectory() as d:
   p,artifact,run=self.fixture(d)
   with artifact:
    m.install_verified(run,artifact)
    argv=run.command.call_args.args[0];kw=run.command.call_args.kwargs
    self.assertEqual(argv,['/fixture/adb','-P','5038','-s','emulator-5556','shell','-T','cmd','package','install','--user','0','--pkg','com.hatsunama.captionaction.captureprobe','-S','256','-'])
    self.assertNotEqual(kw['stdin_fd'],artifact._file.fd);self.assertEqual(kw['stdin_bytes'],256)
    with self.assertRaises(OSError):os.fstat(kw['stdin_fd'])
    self.assertEqual(os.fstat(artifact._file.fd).st_size,256)
    self.assertNotIn(str(p),argv)
 def test_ambiguous_success_failure_stderr_and_nonzero_block(self):
  import install_adapter as m
  for code,out,err in ((1,'Success\n',''),(0,'Success\n','warning'),(0,'Success',''),(0,'Performing Streamed Install\nSuccess\n',''),(0,'Success\nFailure\n',''),(0,'',''),(1,'Failure [INSTALL_FAILED_INVALID_APK]\n','')):
   with self.subTest(code=code,out=out),tempfile.TemporaryDirectory() as d:
    _,artifact,run=self.fixture(d)
    with artifact:
     run.command.return_value=subprocess.CompletedProcess([],code,out,err)
     with self.assertRaises(w.Blocked):m.install_verified(run,artifact)
 def test_swapped_source_before_or_during_install_blocks(self):
  import install_adapter as m
  for during in (False,True):
   with tempfile.TemporaryDirectory() as d:
    p,artifact,run=self.fixture(d)
    def swap(*args,**kwargs):
     replacement=p.with_name('replacement.apk');replacement.write_bytes(b'wrong');replacement.replace(p)
     return subprocess.CompletedProcess([],0,'Success\n','')
    try:
     if during:run.command.side_effect=swap
     else:swap()
     with self.assertRaises(a.ArtifactGateError):m.install_verified(run,artifact)
     if not during:run.command.assert_not_called()
    finally:artifact.close()

class DescriptorEdges(unittest.TestCase):
 def test_readonly_offset_size_closed_and_pipe_rejected(self):
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'fixture.apk';p.write_bytes(b'abc')
   with p.open('rb') as f:
    for size,offset in ((2,0),(4,0),(3,1)):
     f.seek(offset)
     with patch.object(w.subprocess,'Popen') as spawn:
      with self.assertRaises(w.Blocked):w.run_bounded(['unused'],timeout=1,max_output_bytes=20,stdin_fd=f.fileno(),stdin_bytes=size)
      spawn.assert_not_called()
    fd=os.dup(f.fileno());os.close(fd)
    with self.assertRaises(w.Blocked):w.run_bounded(['unused'],timeout=1,max_output_bytes=20,stdin_fd=fd,stdin_bytes=3)
   read,write=os.pipe()
   try:
    with self.assertRaises(w.Blocked):w.run_bounded(['unused'],timeout=1,max_output_bytes=20,stdin_fd=read,stdin_bytes=3)
   finally:os.close(read);os.close(write)
 def test_truncated_input_is_detected(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'fixture.apk';p.write_bytes(b'abc')
   with p.open('rb') as f:
    with self.assertRaisesRegex(w.Blocked,'STDIN_ARTIFACT_CHANGED'):
     w.run_bounded([sys.executable,'-c','import os;open('+repr(str(p))+',"wb").write(b"a");os.read(0,10)'],timeout=2,max_output_bytes=128,stdin_fd=f.fileno(),stdin_bytes=3)
 def test_timeout_and_cancellation_kill_only_owned_child_keep_fd(self):
  from unittest.mock import patch
  for cancel in (False,True):
   with tempfile.TemporaryDirectory() as d:
    p=pathlib.Path(d)/'fixture.apk';p.write_bytes(b'abc');children=[];original=w.subprocess.Popen
    def spawn(*args,**kw):
     child=original(*args,**kw);children.append(child);return child
    with p.open('rb') as f,patch.object(w.subprocess,'Popen',side_effect=spawn):
     if cancel:
      real_sleep=w.time.sleep;interrupted=[False]
      def interrupt_once(seconds):
       if not interrupted[0]:
        interrupted[0]=True;raise KeyboardInterrupt
       return real_sleep(seconds)
      with patch.object(w.time,'sleep',side_effect=interrupt_once),self.assertRaises(KeyboardInterrupt):
       w.run_bounded([sys.executable,'-c','import time;time.sleep(10)'],timeout=1,max_output_bytes=128,stdin_fd=f.fileno(),stdin_bytes=3)
     else:
      with self.assertRaisesRegex(w.Blocked,'TIMEOUT'):
       w.run_bounded([sys.executable,'-c','import time;time.sleep(10)'],timeout=.1,max_output_bytes=128,stdin_fd=f.fileno(),stdin_bytes=3)
     self.assertIsNotNone(children[0].poll());self.assertEqual(os.fstat(f.fileno()).st_size,3)
class DeadlineInitializationCleanup(unittest.TestCase):
 def test_deadline_initialization_failure_terminates_spawned_group(self):
  from unittest.mock import patch
  import signal
  children=[];original=w.subprocess.Popen
  def spawn(*args,**kwargs):
   child=original(*args,**kwargs);children.append(child);return child
  real_clock=w.time.monotonic;called=[False]
  def fail_once():
   if not called[0]:called[0]=True;raise RuntimeError('synthetic deadline initialization failure')
   return real_clock()
  try:
   with patch.object(w.subprocess,'Popen',side_effect=spawn),patch.object(w.time,'monotonic',side_effect=fail_once):
    with self.assertRaisesRegex(RuntimeError,'synthetic deadline'):
     w.run_bounded([sys.executable,'-c','import time;time.sleep(10)'],timeout=1,max_output_bytes=128)
   self.assertEqual(len(children),1)
   self.assertIsNotNone(children[0].poll(),'spawned child escaped bounded runner cleanup')
  finally:
   for child in children:
    if child.poll() is None:
     os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=2)

class AdapterFailureEdges(unittest.TestCase):
 fixture=InstallAdapter.fixture
 def test_short_consumption_even_success_is_not_accepted(self):
  import install_adapter as m
  for consumed in (0,1,255):
   with tempfile.TemporaryDirectory() as d:
    _,artifact,run=self.fixture(d)
    with artifact:
     def short(*args,**kwargs):
      os.read(kwargs['stdin_fd'],consumed);return subprocess.CompletedProcess([],0,'Success\n','')
     run.command.side_effect=short
     with self.assertRaisesRegex(w.Blocked,'INPUT_INCOMPLETE'):m.install_verified(run,artifact)
 def test_symlink_substitution_and_closed_descriptor_rejected(self):
  import install_adapter as m
  for closed in (False,True):
   with tempfile.TemporaryDirectory() as d:
    p,artifact,run=self.fixture(d)
    try:
     if closed:artifact.close()
     else:
      target=p.with_name('other.apk');target.write_bytes(p.read_bytes());p.unlink();p.symlink_to(target)
     with self.assertRaises(a.ArtifactGateError):m.install_verified(run,artifact)
     run.command.assert_not_called()
    finally:
     if artifact._file.fd>=0:artifact.close()
 def test_unowned_endpoint_blocks_without_install(self):
  import inventory_runtime as i,install_adapter as m
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as d:
   _,artifact,_=self.fixture(d);run=i.InventoryRun(pathlib.Path(d),pathlib.Path(d),binding={})
   run.adb='/fixture/adb';run.server=SimpleNamespace(poll=lambda:None);run.emulator=SimpleNamespace(poll=lambda:None)
   with artifact,patch.object(run,'verify_guest_transport',return_value=False),patch.object(i.CaptureRun,'command') as command:
    with self.assertRaises(w.Blocked):m.install_verified(run,artifact)
    command.assert_not_called()
 def test_failed_install_remains_uncertain_without_capture_or_grant(self):
  import inventory_runtime as i
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as d:
   run=i.InventoryRun(pathlib.Path(d),pathlib.Path(d),binding={});artifact=SimpleNamespace(recheck=lambda:None)
   with patch.object(i.boot.Probe,'execute'),patch.object(run,'verify_guest_transport',return_value=True),patch.object(run,'adb_command',return_value='caption_muted_probe\nOK'),patch.object(run,'shell',side_effect=['en-US','Physical size: 480x800','','projection']),patch.object(run,'verified_mute_setup'),patch.object(i.c,'verify_projection_idle'),patch.object(i.install_adapter,'install_verified',side_effect=w.Blocked('INSTALL_STREAM_COMMAND_FAILED')):
    with self.assertRaises(w.Blocked):run.execute_inventory(artifact)
   self.assertTrue(run.installed);self.assertFalse(run.result['capture_started']);self.assertFalse(run.result['permissions_granted'])

class InstallEvidence(unittest.TestCase):
 fixture=InstallAdapter.fixture
 def test_fixed_categories_and_exit_never_retain_sensitive_suffixes(self):
  import install_adapter as m
  cases=[(1,'Failure [INSTALL_FAILED_INVALID_APK: SECRET_PATH]\n','', 'package_rejected'),
         (1,'','error: device offline SECRET_DETAIL','transport_unavailable'),
         (1,'',"error: device 'emulator-5556' not found SECRET_DETAIL",'transport_unavailable'),
         (1,'SECRET_STDOUT','SECRET_STDERR','unknown'),
         (0,'Success\n','SECRET_WARNING','output_contract'),
         (0,'SECRET_STDOUT','','output_contract')]
  for code,out,err,expected in cases:
   with self.subTest(expected=expected),tempfile.TemporaryDirectory() as d:
    _,artifact,run=self.fixture(d);run.result={};run.command.return_value=subprocess.CompletedProcess([],code,out,err)
    with artifact,self.assertRaises(w.Blocked):m.install_verified(run,artifact)
    self.assertEqual(run.result.get('install_exit_code'),code)
    self.assertEqual(run.result.get('install_rejection'),expected)
    self.assertNotIn('SECRET',repr(run.result))
 def test_unknown_installer_token_not_reflected(self):
  import install_adapter as m
  with tempfile.TemporaryDirectory() as d:
   _,artifact,run=self.fixture(d);run.result={};run.command.return_value=subprocess.CompletedProcess([],1,'Failure [SECRET_TOKEN: /SECRET_PATH]\n','')
   with artifact,self.assertRaises(w.Blocked):m.install_verified(run,artifact)
   self.assertEqual(run.result.get('install_rejection'),'unknown');self.assertNotIn('SECRET',repr(run.result))
 def test_schema_accepts_only_bounded_installer_fields(self):
  import failure_diagnostics as f
  data=dict(status='BLOCKED',**f.describe(w.Blocked('INSTALL_STREAM_COMMAND_FAILED'),'inventory-install'),install_exit_code=1,install_rejection='package_rejected')
  f.validate_public_failure(data)
  for key,value in [('install_exit_code',True),('install_exit_code',256),('install_exit_code','SECRET'),('install_rejection','SECRET'),('install_rejection',[])]:
   with self.assertRaises(ValueError):f.validate_public_failure(dict(data,**{key:value}))

class InstallPublicReceipt(unittest.TestCase):
 def test_main_retains_only_safe_install_failure_fields(self):
  import contextlib,io,inventory_runtime as i
  from unittest.mock import patch
  def fail(_artifact):raise w.Blocked('INSTALL_STREAM_COMMAND_FAILED')
  run=SimpleNamespace(result={'scope':'record-audio-inventory-only','runtime_pass':False,'capture_started':False,'permissions_granted':False,'install_exit_code':1,'install_rejection':'package_rejected','raw_stderr':'SECRET_RAW'},stage='inventory-install',execute_inventory=fail,cleanup=lambda:None,filtered_xml=None)
  with patch.object(i,'preflight'),patch.object(i,'verify_artifact',return_value=contextlib.nullcontext(object())),patch.object(i,'source_binding',return_value={}),patch.object(i,'InventoryRun',return_value=run),patch.object(i.tempfile,'mkdtemp',return_value='/tmp/not-created'),patch.object(i.shutil,'rmtree'),patch.object(i.signal,'signal'),patch.object(i,'write_evidence') as evidence,patch.dict(os.environ,{'RUNNER_TEMP':'/tmp'}),patch('sys.argv',['inventory_runtime.py']),contextlib.redirect_stdout(io.StringIO()):
   self.assertEqual(i.main(),1)
  result=evidence.call_args.args[0]
  self.assertEqual(result['install_exit_code'],1);self.assertEqual(result['install_rejection'],'package_rejected');self.assertNotIn('SECRET',repr(result))
  i.failure_diagnostics.validate_public_failure(result)
