from pathlib import Path
import subprocess, hashlib,json
root=Path(__file__).resolve().parents[3]; out=Path(__file__).parent
paths={'audio':'app/src/main/java/com/hatsunama/captionaction/audio/AudioCapture.kt','overlay':'app/src/main/java/com/hatsunama/captionaction/service/CaptionOverlayService.kt'}
before={k:subprocess.check_output(['git','show','1e06a516:'+p],cwd=root,text=True) for k,p in paths.items()}; after={k:(root/p).read_text() for k,p in paths.items()}
def take(s,start,end):return s[s.index(start):s.index(end,s.index(start))].rstrip()
report=[]
for version,sources in [('Before',before),('After',after)]:
 src=sources['audio']
 takechunk=take(src,'    suspend fun takeChunk(', '\n    /**')
 drain=take(src,'    suspend fun drainToNewestWindow(', '\n    /**')
 condition=''
 if version=='Before':
  overlay=sources['overlay']; a=overlay.index('if (norm.isNotEmpty() && norm == lastPublishedNorm)')
  condition=overlay[a+4:overlay.index(') {',a)]
  (out/'original-exact-condition.txt').write_text(condition+'\n')
 for name,body in [('takeChunk',takechunk),('drainToNewestWindow',drain)]:
  report.append({'version':version,'file':paths['audio'],'method':name,'startLine':src[:src.index(body)].count('\n')+1,'sha256':hashlib.sha256(body.encode()).hexdigest()})
 extra='    private val liveWindowDrainer = com.hatsunama.captionaction.audio.LiveWindowDrainer()\n' if version=='After' else ''
 template='''package regression
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

// Hardware boundary only: no AudioRecord or Android services in this JVM fixture.
class VERSIONAudioFixture(ready: List<ShortArray>, arriving: List<ShortArray>) {
    private var record: Any? = Any()
    private val pumpJob: FakePump? = FakePump()
    private val pcmQueue = ScriptedPcmQueue(ready, arriving) {
        record = null
        pumpJob?.isActive = false
    }
    private val overrunCount = AtomicLong(0)
    private val QUEUE_CAPACITY = 24
    private val TAKE_TIMEOUT_MS = 250L
    private val DEFAULT_WINDOW_SAMPLES = 40_000
    private val BACKLOG_CHUNK_THRESHOLD = 4
    private val TAG = "AudioCapture"
EXTRA
TAKE

DRAIN
}
'''.replace('VERSION',version).replace('EXTRA',extra).replace('TAKE\n',takechunk+'\n').replace('DRAIN\n',drain+'\n')
 (out/(version+'AudioFixture.kt')).write_text(template)
 if version=='Before':
  (out/'BeforeDuplicateFixture.kt').write_text('''package regression
// Only the original production exact-duplicate predicate is under test here.
class BeforeDuplicateFixture {
    private var lastPublishedNorm = ""
    fun onPublished(norm: String, nowMs: Long) { lastPublishedNorm = norm }
    fun shouldSuppress(norm: String, nowMs: Long): Boolean = '''+condition+'\n}\n')
(out/'extraction-manifest.json').write_text(json.dumps(report,indent=2)+'\n')
