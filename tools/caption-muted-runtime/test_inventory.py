"""No devices, network, installation or real permissions in these fixtures."""
import hashlib, io, json, pathlib, tarfile, tempfile, unittest
from types import SimpleNamespace
from unittest.mock import patch
import inventory_runtime as i
import isolation
import runtime_contract as c
from boot_probe import Blocked
class InventoryTests(unittest.TestCase):
 def test_missing_binding_stops_before_policy_network_or_boot(self):
  with patch.object(i.artifact_gate,'SIGNED_IDENTITY',None),patch.object(i.boot,'policy') as policy,patch.object(i.urllib.request,'build_opener') as net,patch.object(i.boot.Probe,'execute') as boot:
   with self.assertRaises(i.artifact_gate.ArtifactGateError):i.preflight()
   policy.assert_not_called();net.assert_not_called();boot.assert_not_called()
 def test_old_signed_identity_is_not_reused(self):
  self.assertNotEqual((i.artifact_gate.SIGNED_IDENTITY or {}).get('signed_sha256'),'583bf552e66564656e94d2f5c406b1d4235c82e72997aae634e90a301a077ccf')
  self.assertEqual(i.artifact_gate.UNSIGNED_SHA256,isolation.UNSIGNED_SHA256)
  with patch.object(i,'ROOT_INVENTORY_REVIEW',None):
   with self.assertRaises((Blocked,i.artifact_gate.ArtifactGateError)):i.require_binding()
 def test_entrypoint_has_no_grant_or_capture_route(self):
  with tempfile.TemporaryDirectory() as d:
   run=i.InventoryRun(pathlib.Path(d),pathlib.Path(d),binding={})
   for call in (lambda:run.execute_capture(None),lambda:run.execute_discovery(None,None),lambda:run.tap(None)):
    with self.assertRaises(Blocked):call()
   with patch.object(i.CaptureRun,'tap') as tap:
    run.tap(c.APP_BEGIN)
    with self.assertRaises(Blocked):run.tap(c.APP_BEGIN)
    tap.assert_called_once_with(c.APP_BEGIN)
 def test_complete_uid_inventory_parser(self):
  self.assertEqual(isolation.parse_package_uid_rows(f'package:{c.PACKAGE} uid:10123\n'),[(c.PACKAGE,10123)])
  for text in ('',f'package:{c.PACKAGE} uid:?\n',f'package:{c.PACKAGE} uid:10123\n'*2,'permission denied'):
   with self.assertRaises(Blocked):isolation.parse_package_uid_rows(text)
 def test_source_binding_proves_exact_reviewed_files(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d)
   def archive(mutate=False):
    with tarfile.open(root/'source.tar.gz','w:gz') as t:
     for name in i.SOURCE_HASHES:
      data=(i.HERE/'source'/name).read_bytes()
      if mutate and name=='ProbeService.kt':data=data.replace(b'.addMatchingUid(Process.myUid())',b'.addMatchingUid(1000)')
      info=tarfile.TarInfo('app/src/'+name);info.size=len(data);t.addfile(info,io.BytesIO(data))
    return hashlib.sha256((root/'source.tar.gz').read_bytes()).hexdigest()
   for mutate in (False,True):
    sha=archive(mutate);artifact=SimpleNamespace(recheck=lambda:None,identity={'source_sha256':sha})
    with patch.object(i,'staging',return_value=root):
     if mutate:
      with self.assertRaises(Blocked):i.source_binding(artifact)
     else:isolation.verify_binding(i.source_binding(artifact))
 def test_filtered_xml_not_raw_hierarchy(self):
  facts={'owner':'com.android.permissioncontroller','foreground_component':'com.android.permissioncontroller/.GrantPermissionsActivity','allowlisted_nodes':[{'text_key':'probe-record-audio-request','resource_id':'android:id/message','enabled':True}]}
  xml=i.filtered_structure(facts);self.assertIn('raw-hierarchy="false"',xml);self.assertNotIn('<hierarchy',xml)
 def test_evidence_bounded_only_fixed_files_and_no_overwrite(self):
  with tempfile.TemporaryDirectory() as d,patch.dict(i.os.environ,{'RUNNER_TEMP':d}):
   i.write_evidence({'status':'BLOCKED','runtime_pass':False})
   dest=pathlib.Path(d)/'caption-record-inventory';self.assertEqual([p.name for p in dest.iterdir()],['inventory.json'])
   with self.assertRaises(FileExistsError):i.write_evidence({})
  with tempfile.TemporaryDirectory() as d,patch.dict(i.os.environ,{'RUNNER_TEMP':d}):
   with self.assertRaises(Blocked):i.write_evidence({'text':'x'*262145})
 def test_download_redirect_denied(self):
  with self.assertRaises(Blocked):i.NoRedirect().redirect_request(None,None,None,None,None,None)

class PublishedBindingTests(unittest.TestCase):
 def test_approved_A_delivery_and_scope(self):
  ident=i.require_binding()
  self.assertEqual(ident['delivery_parent'],'8a976164b509ba5403bb3f371d88733ef19bf70b')
  self.assertEqual(i.ROOT_INVENTORY_REVIEW['source_parent'],'893572ac39c32fdebd7bf7ecdc7652f545f4eb92')
  self.assertNotEqual(i.ROOT_INVENTORY_REVIEW['source_parent'],ident['delivery_parent'])
  self.assertEqual(i.ROOT_INVENTORY_REVIEW['scope'],'own-dialog-no-grant-no-projection-no-capture')
  for row in i.DOWNLOAD_MANIFEST:self.assertIn('/'+ident['delivery_parent']+'/',row['url'])
 def test_missing_or_changed_root_review_blocks(self):
  for value in (None,{},dict(mode='capture')):
   with patch.object(i,'ROOT_INVENTORY_REVIEW',value):
    with self.assertRaises(Blocked):i.require_binding()
 def test_changed_download_digest_blocks(self):
  rows=[dict(r) for r in i.DOWNLOAD_MANIFEST];rows[0]['sha256']='0'*64
  with patch.object(i,'DOWNLOAD_MANIFEST',rows):
   with self.assertRaises(Blocked):i.require_binding()

class SuccessorSourceBinding(unittest.TestCase):
 def test_successor_changes_only_source_parent_not_assets(self):
  self.assertEqual(i.ROOT_INVENTORY_REVIEW['source_parent'],'893572ac39c32fdebd7bf7ecdc7652f545f4eb92')
  self.assertEqual(i.artifact_gate.DELIVERY_PARENT,'8a976164b509ba5403bb3f371d88733ef19bf70b')
  self.assertEqual(i.artifact_gate.SIGNED_IDENTITY['signed_sha256'],'43e88278a61b5209aebf477ff1029aa2804c1a118b93e362b7dd9877334448cc')
  self.assertEqual(i.artifact_gate.SIGNED_IDENTITY['receipt_sha256'],'e6f4917b4650d750582dd012edc2969c413cc71b209ddfc554b72a75dc55f60b')
  for row in i.DOWNLOAD_MANIFEST:
   self.assertIn('/8a976164b509ba5403bb3f371d88733ef19bf70b/',row['url'])
   self.assertNotIn('/893572ac39c32fdebd7bf7ecdc7652f545f4eb92/',row['url'])
