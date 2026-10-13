"""Only synthetic command doubles and tiny local Python children; never real ADB/emulator."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import safety_watchdog as w
from runtime_contract import Blocked, PACKAGE
from test_runtime_contract import package_fixture


class Watchdog(unittest.TestCase):
    def test_scoped_force_stop_and_readback(self):
        runner=Mock(side_effect=[subprocess.CompletedProcess([],0,'',''),subprocess.CompletedProcess([],1,'',''),
                                subprocess.CompletedProcess([],0,package_fixture(),'')])
        self.assertTrue(w.force_stop_verified('/fixture/adb',runner))
        for call in runner.call_args_list:
            self.assertEqual(call.args[0][:6],['/fixture/adb','-P','5038','-s','emulator-5556','shell'])
            self.assertEqual(call.kwargs['timeout'],2)
            self.assertIn(PACKAGE,call.args[0])
        self.assertEqual(runner.call_args_list[0].args[0][6:],['am','force-stop','--user','0',PACKAGE])

    def test_failed_stop_process_survival_and_unknown_readback(self):
        for outputs in ([subprocess.CompletedProcess([],1,'','failed')],
                        [subprocess.CompletedProcess([],0,'',''),subprocess.CompletedProcess([],0,'123','')],
                        [subprocess.CompletedProcess([],0,'',''),subprocess.CompletedProcess([],1,'','offline')],
                        [subprocess.CompletedProcess([],0,'',''),subprocess.CompletedProcess([],1,'',''),
                         subprocess.CompletedProcess([],0,package_fixture('false'),'')]):
            with self.assertRaises(Blocked): w.force_stop_verified('/fixture/adb',Mock(side_effect=outputs))

    def test_watchdog_preserves_disposable_home_and_disabled_device_discovery(self):
        with patch.dict(os.environ, {'HOME':'/fixture/disposable-home','SECRET_TOKEN':'must-not-copy'}):
            env=w.watchdog_environment()
        self.assertEqual(env['HOME'],'/fixture/disposable-home')
        self.assertEqual(env['ADB_USB'],'0');self.assertEqual(env['ADB_MDNS'],'0')
        self.assertEqual(env['ADB_SERVER_SOCKET'],'tcp:localhost:5038')
        self.assertNotIn('SECRET_TOKEN',env)

    def test_bounded_local_child_timeout_and_output(self):
        with self.assertRaisesRegex(Blocked,'TIMEOUT'):
            w.run_bounded([sys.executable,'-c','import time;time.sleep(5)'],timeout=.1,max_output_bytes=1000)
        with self.assertRaisesRegex(Blocked,'OUTPUT_LIMIT'):
            w.run_bounded([sys.executable,'-c','print("x"*2000)'],timeout=1,max_output_bytes=1000)

    def run_independent(self, fail_adb):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);log=root/'calls';stub=root/'fake_adb.py'
            stub.write_text('#!'+sys.executable+'\nimport sys\nfrom pathlib import Path\n'
                +'with Path('+repr(str(log))+').open("a") as f:f.write(" ".join(sys.argv[1:])+"\\n")\n'
                +('raise SystemExit(4)\n' if fail_adb else
                  'if "pidof" in sys.argv:raise SystemExit(1)\n'
                  +'if "package" in sys.argv:print('+repr(package_fixture())+')\n'))
            stub.chmod(0o700)  # Test-owned Python fixture only, no installed software.
            guest=subprocess.Popen([sys.executable,'-c','import time;time.sleep(10)'],start_new_session=True)
            read_fd,write_fd=os.pipe()
            identity=w.owned_emulator_identity(guest.pid)
            result=root/'result.json';ready=root/'ready'
            watch=subprocess.Popen([sys.executable,w.__file__,'--fd',str(read_fd),'--deadline',str(time.monotonic()+.5),
                '--adb',str(stub),'--identity',json.dumps(identity),'--receipt',str(result),'--ready',str(ready)],
                pass_fds=(read_fd,),start_new_session=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            os.close(read_fd)
            try:
                # This stands in for a stuck main UI/metrics parser. No parent polling.
                time.sleep(1.2)
                stdout,stderr=watch.communicate(timeout=4)
                self.assertEqual(watch.returncode,2,(stdout,stderr))
                receipt=json.loads(result.read_text())
                self.assertEqual(receipt['event'],'deadline')
                self.assertIn('am force-stop --user 0 '+PACKAGE,log.read_text())
                if fail_adb:
                    self.assertTrue(receipt['guest_destroyed']);guest.wait(timeout=2)
                else:self.assertTrue(receipt['stopped'])
            finally:
                os.close(write_fd)
                for child in (watch,guest):
                    try:os.killpg(child.pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                    child.wait(timeout=2)

    def test_real_independent_deadline_while_parent_parser_is_blocked(self):
        self.run_independent(False)

    def test_adb_failure_escalates_only_owned_guest_group(self):
        self.run_independent(True)

    def test_wrong_pid_starttime_or_uid_is_never_killed(self):
        identity={'pid':4000,'uid':os.getuid(),'pgrp':4000,'starttime':123}
        with patch.object(w,'owned_emulator_identity',return_value={**identity,'starttime':456}),patch.object(w.os,'killpg') as kill:
            with self.assertRaisesRegex(Blocked,'IDENTITY_CHANGED'):w.destroy_owned_guest(identity)
            kill.assert_not_called()

    def test_parent_death_eof_stops_without_waiting_for_deadline(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);r,write=os.pipe();os.close(write)
            identity={'pid':4000,'uid':os.getuid(),'pgrp':4000,'starttime':123}
            with patch.object(w,'owned_emulator_identity',return_value=identity),patch.object(w,'force_stop_verified',return_value=True) as stop:
                try:self.assertEqual(w.watch(r,time.monotonic()+30,'fake',identity,root/'r',root/'ready'),2)
                finally:os.close(r)
                stop.assert_called_once()
                self.assertEqual(json.loads((root/'r').read_text())['event'],'parent_closed')

    def test_parent_disarm_has_separate_receipt(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);r,write=os.pipe();os.write(write,b'D')
            identity={'pid':4000,'uid':os.getuid(),'pgrp':4000,'starttime':123}
            with patch.object(w,'owned_emulator_identity',return_value=identity),patch.object(w,'force_stop_verified') as stop:
                try:self.assertEqual(w.watch(r,time.monotonic()+30,'fake',identity,root/'r',root/'ready'),0)
                finally:os.close(r);os.close(write)
                stop.assert_not_called()
                self.assertEqual(json.loads((root/'r').read_text()),{'event':'disarmed','stopped':True})


if __name__ == '__main__':unittest.main(verbosity=2)
