"""Offline synthetic regressions, never evidence of an actual permission UI."""
import json, pathlib, unittest
import runtime_contract as old
from test_runtime_contract import audio_fixture, policy_fixture, sessions_fixture, good_metrics
import isolation as c
ROOT = pathlib.Path(__file__).resolve().parent

def player(uid=1000):
 return f'  AudioPlaybackConfiguration piid:15 deviceIds:[] type:android.media.SoundPool u/pid:{uid}/917 state:idle attr:AudioAttributes: usage=USAGE_ASSISTANCE_SONIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x800 tags= bundle=null sessionId:0 mutedState:none FormatInfo{{isSpatialized=false, channelMask=0x0, sampleRate=0}}'
def audio(uid=1000):
 return audio_fixture().replace('  players:\n', '  players:\n'+player(uid)+'\n')
def policy():
 return policy_fixture().replace('devices (1)', 'devices (2)', 1).replace('\n Available input', '  2. Port ID: 5; "Telephony"; {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}\n Available input')
def sessions(): return sessions_fixture().replace('session is null', 'session is synthetic-priority-object')
def binding():
 return {'artifact_source_bound':True,'unsigned_sha256': 'dda71638acd519f8ec37c6a31738c63c4ffda664bdfff33e0003c5e7fb63761f', 'uid_rules':['Process.myUid()'],'usage_rules':['USAGE_MEDIA'],'mic_fallback':False,'shared_user_id':False,'internet':False,'camera':False,'virtual_display':False}
def proof():
 return {'uid':10123,'package_uid_rows':[(old.PACKAGE,10123)],'binding':binding(),'owned_emulator':True,'audio_volume':0,'media_volume':0}
class RevisedIsolation(unittest.TestCase):
 def check(self,a=None,p=None,s=None,ctx=None):return c.verify_probe_isolation(a or audio(),p or policy(),s or sessions(),**(ctx or proof()))
 def test_old_guard_red_exact_root_cause(self):
  with self.assertRaisesRegex(old.Blocked,'PLAYBACK_OBJECT_PRESENT_OR_UNKNOWN'): old.verify_audio_idle(audio(),policy(),sessions())
 def test_other_uid_soundpool_excluded_activity_unknown(self):
  r=self.check(); self.assertEqual(r['other_uid_players'][0]['activity'],'UNKNOWN'); self.assertTrue(r['other_uid_players'][0]['excluded_by_uid']); self.assertEqual(r['priority_session'],'present_quality_unknown'); self.assertFalse(r['runtime_pass'])
 def test_recorded_redacted_other_uid_objects_not_declared_inactive(self):
  recorded=json.loads((ROOT/'fixtures/recorded-idle-result.json').read_text())
  a=audio_fixture().replace('  players:\n','  players:\n'+'\n'.join(recorded['current_audio']['players']['current_lines'])+'\n')
  with self.assertRaisesRegex(old.Blocked,'PLAYBACK_OBJECT_PRESENT_OR_UNKNOWN'):old.verify_audio_idle(a,policy(),sessions())
  r=self.check(a=a);self.assertEqual([p['uid'] for p in r['other_uid_players']],[1000,10185]);self.assertEqual([p['activity'] for p in r['other_uid_players']],['UNKNOWN','UNKNOWN'])
 def test_own_uid_unexpected_blocks(self):
  with self.assertRaises(old.Blocked): self.check(a=audio(10123))
 def test_uid_mapping_missing_ambiguous_blocks(self):
  for rows in ([],[(old.PACKAGE,10123),('other',10123)],[(old.PACKAGE,10124)],[(old.PACKAGE,10123)]*2):
   ctx=proof();ctx['package_uid_rows']=rows
   with self.subTest(rows=rows),self.assertRaises(old.Blocked):self.check(ctx=ctx)
 def test_missing_uid_blocks(self):
  for uid in (None,True,-1,9999):
   ctx=proof();ctx['uid']=uid
   with self.assertRaises(old.Blocked):self.check(ctx=ctx)
 def test_filters_and_binding_fail_closed(self):
  for k,v in [('uid_rules',[]),('uid_rules',['Process.myUid()','1000']),('usage_rules',[]),('usage_rules',['USAGE_MEDIA','USAGE_GAME']),('mic_fallback',True),('shared_user_id',True),('internet',True),('camera',True),('virtual_display',True),('artifact_source_bound',False),('unsigned_sha256','0'*64)]:
   ctx=proof();ctx['binding'][k]=v
   with self.subTest(k=k,v=v),self.assertRaises(old.Blocked):self.check(ctx=ctx)
 def test_unknown_emulator_nonzero_volume_blocks(self):
  for k,v in [('owned_emulator',False),('owned_emulator',None),('audio_volume',1),('media_volume',1)]:
   ctx=proof();ctx[k]=v
   with self.assertRaises(old.Blocked):self.check(ctx=ctx)
 def test_unknown_uid_or_pid_sentinels_block(self):
  for uid in (4294967295,9999999999):
   with self.assertRaises(old.Blocked):self.check(a=audio(uid))
  for pid in (0,2147483648,9999999999):
   with self.assertRaises(old.Blocked):self.check(a=audio().replace('/917 ',f'/{pid} '))
 def test_missing_unknown_player_mapping_blocks(self):
  for bad in [audio().replace('u/pid:1000/917','u/pid:?/917'),audio().replace('  players:\n','  players:\n unknown\n')]:
   with self.assertRaises(old.Blocked): self.check(a=bad)
 def test_speaker_passive18_inventory_only_actual18_blocked(self):
  self.assertEqual(self.check()['speaker_port_id'],2);self.assertTrue(c.verify_actual_track_route(2,2,2))
  for expected,actual,kind in [(2,5,18),(2,2,18),(2,2,8),(2,2,None),(2,3,2)]:
   with self.assertRaises(old.Blocked): c.verify_actual_track_route(expected,actual,kind)
 def test_multiline_nested_profiles_are_not_device_entries(self):
  p=policy().replace('\n Available input', '\n     Encapsulation modes: 0, metadata types: 0\n     1. Profile 0: AUDIO_FORMAT_PCM_16_BIT\n        Sampling rates: 48000\n Available input')
  self.assertEqual(self.check(p=p)['speaker_port_id'],2)
  with self.assertRaises(old.Blocked):self.check(p=p.replace('Sampling rates: 48000','AUDIO_DEVICE_OUT_BLUETOOTH_A2DP'))
 def test_port_zero_and_nonsequential_ordinals_block(self):
  for p in (policy().replace('Port ID: 2;', 'Port ID: 0;'),policy().replace('  2. Port ID:', '  9. Port ID:')):
   with self.assertRaises(old.Blocked):self.check(p=p)
 def test_external_output_blocks(self):
  with self.assertRaises(old.Blocked): self.check(p=policy().replace('TELEPHONY_TX','BLUETOOTH_A2DP'))
 def test_priority_never_rescues_failed_metrics(self):
  self.check()
  with self.assertRaises(old.Blocked):old.parse_metrics(good_metrics().replace('4200.000','99.000'))
 def test_recorder_and_projection_guards_unchanged(self):
  with self.assertRaises(old.Blocked): self.check(a=audio().replace('dump time: 1:00 PM\n','dump time: 1:00 PM\nriid 3; active? false'))
  with self.assertRaises(old.Blocked):old.verify_projection_idle('Media Projection: unknown')
if __name__=='__main__':unittest.main()
