import unittest
import guest_transport as g
from boot_probe import Blocked
HEADER='sl local_address rem_address st tx_queue rx_queue tr tm retr uid timeout inode\n'
def table(port=5556,inode=11,address='0100007F'):
 return f'  0: {address}:{port:04X} 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 {inode}\n'
class Transport(unittest.TestCase):
 def test_preexisting_any_target_port_blocks(self):
  for port in (5556,5557):
   with self.assertRaises(Blocked):g.require_ports_absent(HEADER+table(port),HEADER)
 def test_empty_ports_prelaunch_only(self):
  g.require_ports_absent(HEADER,HEADER);self.assertFalse(g.verify_endpoints(HEADER,HEADER,set()))
 def test_own_loopback_both_ports_pass(self):self.assertTrue(g.verify_endpoints(HEADER+table()+table(5557,12),HEADER,{11,12}))
 def test_wrong_inode_external_or_unknown_blocks(self):
  for text,inodes in [(HEADER+table()+table(5557,12),{11}), (HEADER+table(address='00000000'),{11}),('broken\nbad',set())]:
   with self.assertRaises(Blocked):g.verify_endpoints(text,HEADER,inodes)
 def test_one_port_not_ready(self):self.assertFalse(g.verify_endpoints(HEADER+table(),HEADER,{11}))
