#!/usr/bin/env python3
"""Offline JVM tests of verbatim production launch/finally/onDestroy source slices.
Platform stand-ins are not Android runtime evidence. No Gradle or downloads.
"""
from pathlib import Path
import subprocess, os, json, hashlib, sys
r=Path(__file__).resolve().parents[1]
c=Path(os.environ['GRADLE_USER_HOME'])/'caches/modules-2/files-2.1'
j=str(Path(os.environ['JAVA_HOME'])/'bin/java')
variant=sys.argv[1] if len(sys.argv)>1 else 'actual'
out=r/'evidence'/('early-stop-'+variant); out.mkdir(parents=True,exist_ok=True)
main=r/'app/src/main/java/com/hatsunama/captionaction/captureprobe'
source=Path(sys.argv[2]) if len(sys.argv)>2 else main/'ProbeService.kt'
s=source.read_text()
assert 'CoroutineScope(SupervisorJob() + Dispatchers.IO)' in s
opt_in='@OptIn(ExperimentalCoroutinesApi::class)' if '    @OptIn(ExperimentalCoroutinesApi::class)\n    override fun onStartCommand' in s else ''
# Exact slices, including guard arming and scope.launch policy. Fail closed on layout drift.
start=s[s.index('        speakerId = speaker.id;'):s.index('    private suspend fun runPhase')]
assert start.endswith('    }\n')
start=start[:-6]
destroy=s[s.index('    override fun onDestroy()'):s.rindex('\n}')]
assert 'scope.launch' in start and 'finally {' in start and 'scope.cancel()' in destroy
fixture=(r/'scripts/jvm/LaunchFixture.kt.template').read_text().replace('/* ACTUAL_START */',start).replace('/* ACTUAL_DESTROY */',destroy).replace('/* ACTUAL_OPT_IN */',opt_in)
f=out/'LaunchFixture.kt'; f.write_text(fixture)
def jar(g,a,v): return str(next((c/g/a/v).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','1.9.24'); junit=jar('junit','junit','4.13.2'); ham=jar('org.hamcrest','hamcrest-core','1.3'); ann=jar('org.jetbrains','annotations','13.0')
coroutines=jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.1')
compiler=':'.join([jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','1.9.24'),stdlib,jar('org.jetbrains.kotlin','kotlin-script-runtime','1.9.24'),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),ann])
cp=':'.join([stdlib,junit,ham,ann,coroutines]); classes=out/'classes'; classes.mkdir(exist_ok=True)
tests=sorted((r/'app/src/test/java/com/hatsunama/captionaction/captureprobe').glob('*.kt'))+[r/'scripts/jvm/ProbeLaunchLifecycleTest.kt']
sources=[p for p in main.glob('*.kt') if p.name not in ('ProbeService.kt','ProbeActivity.kt')]+[f]+tests
env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JAVA_OPTS','GRADLE_OPTS')}
args=[j,'-Xmx192m','-XX:MaxMetaspaceSize=128m','-XX:ActiveProcessorCount=1']
commands=[args+['-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes)]+list(map(str,sources)),args+['-cp',str(classes)+':'+cp,'org.junit.runner.JUnitCore']+['com.hatsunama.captionaction.captureprobe.'+p.stem for p in tests]]
codes=[]
for i,cmd in enumerate(commands):
 result=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90,env=env)
 (out/('compile.txt' if i==0 else 'tests.txt')).write_text(result.stdout); codes.append(result.returncode)
 if result.returncode: print(result.stdout)
 if i==0 and result.returncode: break
(out/'receipt.json').write_text(json.dumps({'exit_codes':codes,'service_sha256':hashlib.sha256(s.encode()).hexdigest(),'verbatim_launch_slice_sha256':hashlib.sha256(start.encode()).hexdigest(),'verbatim_destroy_slice_sha256':hashlib.sha256(destroy.encode()).hexdigest(),'coroutines_sha256':hashlib.sha256(Path(coroutines).read_bytes()).hexdigest(),'coroutines_version':'1.8.1'},indent=2))
print(variant,codes)
if len(codes)==2: print((out/'tests.txt').read_text())
sys.exit(max(codes))
