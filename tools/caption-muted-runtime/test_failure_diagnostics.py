"""Pure injected failures only: no devices, network or guest operations."""
import contextlib,errno,io,os,unittest
from types import SimpleNamespace
from unittest.mock import patch
import inventory_runtime as i
class FailureDiagnostics(unittest.TestCase):
 def receipt(self,stage,error):
  def fail(_artifact):raise error
  run=SimpleNamespace(result={'status':'blocked','scope':'record-audio-inventory-only','runtime_pass':False,'capture_started':False,'permissions_granted':False},stage=stage,execute_inventory=fail,cleanup=lambda:None,filtered_xml=None)
  with patch.object(i,'preflight'),patch.object(i,'verify_artifact',return_value=contextlib.nullcontext(object())),patch.object(i,'source_binding',return_value={}),patch.object(i,'InventoryRun',return_value=run),patch.object(i.tempfile,'mkdtemp',return_value='/tmp/not-created'),patch.object(i.shutil,'rmtree'),patch.object(i.signal,'signal'),patch.object(i,'write_evidence') as evidence,patch.dict(os.environ,{'RUNNER_TEMP':'/tmp'}),patch('sys.argv',['inventory_runtime.py']),contextlib.redirect_stdout(io.StringIO()):
   self.assertEqual(i.main(),1)
  return evidence.call_args.args[0]
 def test_distinct_safe_phases_errno_survive_main(self):
  for phase in ('package-installation','emulator-boot'):
   r=self.receipt(phase,PermissionError(errno.EACCES,'SECRET_DETAIL','/private/SECRET_PATH'))
   self.assertEqual(r.get('failure_stage'),phase)
   self.assertEqual(r.get('failure_errno'),errno.EACCES)
   self.assertNotIn('SECRET',repr(r))
 def test_unknown_exception_and_phase_never_leak(self):
  private=type('SECRET_CLASS',(Exception,),{})('SECRET_MESSAGE')
  r=self.receipt('/private/SECRET_PHASE',private)
  self.assertEqual(r.get('failure_stage'),'unknown')
  self.assertEqual(r['reason'],'UNEXPECTED_EXCEPTION')
  self.assertNotIn('SECRET',repr(r))
 def test_blocked_sensitive_message_never_leaks(self):
  r=self.receipt('emulator-boot',i.Blocked('/private/SECRET_PATH'))
  self.assertEqual(r['reason'],'GUARD_BLOCKED')
  self.assertNotIn('SECRET',repr(r))

class FailureSchema(unittest.TestCase):
 def test_every_allowed_phase_roundtrips(self):
  import failure_diagnostics as f
  for stage in f.PHASES:
   with self.subTest(stage=stage):
    data=f.describe(PermissionError(errno.EACCES,'SECRET','/SECRET'),stage)
    self.assertEqual(data['failure_stage'],stage);self.assertEqual(data['failure_errno'],errno.EACCES)
    f.validate_public_failure(dict(status='BLOCKED',**data))
    self.assertNotIn('SECRET',repr(data))
 def test_known_operation_from_real_function_frame(self):
  import failure_diagnostics as f,guest_transport as g
  with patch.object(g,'owned_emulator_identity',side_effect=PermissionError(errno.EACCES,'SECRET','/SECRET')):
   try:g.owned_sockets({'pid':123})
   except PermissionError as error:data=f.describe(error,'emulator-boot')
  self.assertEqual(data['failure_operation'],'owned-socket-inventory')
  self.assertEqual(data['failure_errno'],errno.EACCES)
  self.assertNotIn('SECRET',repr(data))
 def test_all_unknown_metadata_types_and_values_block(self):
  import failure_diagnostics as f
  base=dict(status='BLOCKED',**f.describe(PermissionError(errno.EACCES,'SECRET'),'emulator-boot'))
  for key,values in {'failure_stage':['/SECRET',[],None,1],'failure_operation':['SECRET',[],None,1],
                     'reason':['SECRET',[],None,1],'failure_errno':['13',True,-1,999999,[]]}.items():
   for value in values:
    with self.subTest(key=key,value=value),self.assertRaises(ValueError):f.validate_public_failure(dict(base,**{key:value}))
  for key in f.FIELDS:
   bad=dict(base);del bad[key]
   with self.assertRaises(ValueError):f.validate_public_failure(bad)
  with self.assertRaises(ValueError):f.validate_public_failure(dict(base,status='INVENTORY_STOPPED'))
 def test_bad_errno_values_are_not_serialized(self):
  import failure_diagnostics as f
  for number in ('SECRET',True,-1,999999,[]):
   e=OSError();e.errno=number
   self.assertIsNone(f.describe(e,'emulator-boot')['failure_errno'])
 def test_failure_extra_fields_are_rejected(self):
  import failure_diagnostics as f
  data=dict(status='BLOCKED',**f.describe(PermissionError(errno.EACCES,'SECRET'),'emulator-boot'))
  with self.assertRaises(ValueError):f.validate_public_failure(dict(data,secret_path='/SECRET'))

class GuardCodes(unittest.TestCase):
 def test_static_known_guard_codes_preserved_without_private_suffix(self):
  import failure_diagnostics as f
  for code in ('OWN_GUEST_TRANSPORT_NOT_READY','EMULATOR_ENDPOINT_NOT_OWNED_LOOPBACK','COMMAND_FAILED'):
   self.assertEqual(f.describe(i.Blocked(code),'emulator-boot')['reason'],code)
   self.assertEqual(f.describe(i.Blocked(code+':/private/SECRET_PATH'),'emulator-boot')['reason'],code)
 def test_unknown_guard_code_never_reflected(self):
  import failure_diagnostics as f
  for msg in ('SECRET_UPPERCASE','/private/SECRET_PATH','SECRET:OWN_GUEST_TRANSPORT_NOT_READY'):
   self.assertEqual(f.describe(i.Blocked(msg),'emulator-boot')['reason'],'GUARD_BLOCKED')

class ExhaustiveStaticMetadata(unittest.TestCase):
 def test_each_guard_has_canonical_safe_output(self):
  import failure_diagnostics as f
  for code in f.GUARD_CODES:
   with self.subTest(code=code):
    data=f.describe(i.Blocked(code+':/SECRET_PATH SECRET_VALUE'),'emulator-boot')
    self.assertEqual(data['reason'],code)
    self.assertNotIn('SECRET',repr(data))
    f.validate_public_failure(dict(status='BLOCKED',**data))
 def test_each_static_operation_ignores_traceback_filename(self):
  import failure_diagnostics as f
  for (module,name),label in f._OPERATIONS.items():
   with self.subTest(module=module,name=name):
    ns={'__name__':module}
    exec(compile('def '+name+'():\n raise PermissionError(13,"SECRET_TEXT","/SECRET_PATH")\n','/SECRET_TRACEBACK_FILENAME','exec'),ns)
    try:ns[name]()
    except PermissionError as e:data=f.describe(e,'emulator-boot')
    self.assertEqual(data['failure_operation'],label)
    self.assertNotIn('SECRET',repr(data))
    f.validate_public_failure(dict(status='BLOCKED',**data))

class ActualCliName(unittest.TestCase):
 def test_inventory_entrypoint_frame_as_main_is_known(self):
  import failure_diagnostics as f,inspect
  ns={'__name__':'__main__'}
  exec(compile(inspect.getsource(i.filtered_structure),'/PRIVATE_TRACEBACK_FILENAME','exec'),ns)
  try:ns['filtered_structure'](None)
  except Exception as error:data=f.describe(error,'inventory-dialog-serialization')
  self.assertEqual(data['failure_operation'],'filtered-dialog-serialization')
  self.assertNotIn('PRIVATE',repr(data))
