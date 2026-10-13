import hashlib,json,pathlib,unittest
from unittest.mock import patch
import artifact_gate as a
class PublicSummary(unittest.TestCase):
 def test_exact_new_summary_bound(self):
  b=(pathlib.Path(__file__).parent/'fixtures/PUBLIC_DIAGNOSTIC_VERIFICATION.json').read_bytes()
  self.assertEqual(hashlib.sha256(b).hexdigest(),a.OBSERVED_SIGNED_IDENTITY['receipt_sha256'])
  r=a.verify_receipt(b,a.OBSERVED_SIGNED_IDENTITY,[{}]*18)
  self.assertEqual(r['schema_version'],2)
  for key in ('private_signing_receipt_sha256','new_key_created_or_exported','runtime_executed','package','permissions'):self.assertNotIn(key,r)
  for key in ('signed_path','resources','disk_free_bytes'):self.assertNotIn(key,r)
 def test_old_schema_or_changed_fields_block(self):
  r=dict(a.PUBLIC_SUMMARY_FACTS)
  for key,value in [('signed_sha256','0'*64),('payload_entries',19),('signature_scheme_v3',False),('signed_path','private'),('private_signing_receipt_sha256','0'*64),('new_key_created_or_exported',False)]:
   bad={**r,key:value};b=json.dumps(bad).encode();identity={**a.OBSERVED_SIGNED_IDENTITY,'receipt_sha256':hashlib.sha256(b).hexdigest()}
   with self.assertRaises(a.ArtifactGateError):a.verify_receipt(b,identity,[{}]*18)
 def test_observed_identity_cannot_self_approve(self):
  with patch.object(a,'SIGNED_IDENTITY',a.OBSERVED_SIGNED_IDENTITY):
   with self.assertRaises(a.ArtifactGateError):a.require_approved_identity(None)
