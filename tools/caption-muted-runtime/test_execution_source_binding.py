"""Separate org workflow identity from immutable personal-repo runtime checkout."""
import os,unittest
from unittest.mock import patch
import inventory_runtime as i
import test_boot_probe as boot_tests
class ExecutionSourceBinding(unittest.TestCase):
 def env(self):return {**boot_tests.Guards().env(),'PROBE_CELL':'api36'}
 def check(self, outputs):
  with patch.object(i,'require_binding',return_value={'fixture':True}),patch.object(i,'ROOT_INVENTORY_REVIEW',{'source_parent':'c'*40}),patch.dict(os.environ,self.env(),clear=True),patch.object(i.subprocess,'check_output',side_effect=outputs):return i.preflight()
 def test_exact_runtime_source_checkout_not_execution_checkout(self):
  self.assertEqual(self.check(['a'*40,'c'*40,'https://github.com/Hatsunama/Caption-Action']),{'fixture':True})
  self.assertEqual(self.check(['a'*40,'c'*40,'https://github.com/Hatsunama/Caption-Action.git']),{'fixture':True})
 def test_org_checkout_wrong_source_commit_parent_or_origin_blocks(self):
  cases=[['b'*40,'c'*40,'https://github.com/Hatsunama/Caption-Action'],['a'*40,'d'*40,'https://github.com/Hatsunama/Caption-Action'],['a'*40,'c'*40,'https://github.com/xMilo-At-Your-Side/Caption-Action-build'],['a'*40,'c'*40,'https://example.com/Hatsunama/Caption-Action']]
  for outputs in cases:
   with self.subTest(outputs=outputs),self.assertRaises(i.Blocked):self.check(outputs)
