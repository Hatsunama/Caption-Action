#!/usr/bin/env python3
"""Fail closed on complete test evidence and unsigned diagnostic packaging."""
from pathlib import Path
import hashlib, json, os, re, subprocess, xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[1]
xmls=sorted((r/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert len(xmls)==5, f'Expected 5 Gradle XML suites, got {len(xmls)}'
suites=[ET.parse(p).getroot() for p in xmls]
assert sum(int(s.attrib['tests']) for s in suites)==25
assert all(int(s.attrib.get(k,'0'))==0 for s in suites for k in ['failures','errors','skipped'])
lifecycle=r/'evidence/early-stop-ci'
receipt=json.loads((lifecycle/'receipt.json').read_text())
assert receipt['exit_codes']==[0,0]
assert re.search(r'OK \(32 tests\)',(lifecycle/'tests.txt').read_text())
assert receipt['service_sha256']=='d5cc560d577769296d8e3b4f70b954d7920e98e7f20c1c1858b5df415251e477'
apks=list((r/'app/build/outputs/apk/debug').glob('*.apk')); assert len(apks)==1
apk=apks[0]
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
v=subprocess.run([str(tools/'apksigner'),'verify','--verbose',str(apk)],capture_output=True,text=True)
assert v.returncode!=0 and 'DOES NOT VERIFY' in v.stderr+v.stdout, 'APK must fail signature verification as unsigned'
a=subprocess.run([str(tools/'aapt'),'dump','badging',str(apk)],capture_output=True,text=True,check=True).stdout
assert "package: name='com.hatsunama.captionaction.captureprobe'" in a
assert 'android.permission.INTERNET' not in a
result={'commit':os.environ.get('GITHUB_SHA'),'gradle_tests':25,'script_tests':32,'structural_gates':9,'service_sha256':receipt['service_sha256'],'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apk_bytes':apk.stat().st_size,'unsigned':True,'runtime_tested':False}
(r/'ci-receipt.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
for p in xmls: print(p.name, p.read_text())
