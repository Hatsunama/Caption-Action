from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[3]; out=Path(__file__).parent
base='1e06a516'
def body(s, start):
 a=s.index('{',start); n=1; i=a+1
 while n:
  if s[i]=='{':n+=1
  if s[i]=='}':n-=1
  i+=1
 return s[a+1:i-1]
for mode in ['before','after']:
 d=out/mode; d.mkdir(exist_ok=True)
 for name in ['service/ModelDownloadManager.kt','data/ModelCache.kt','data/ModelTier.kt']:
  p='app/src/main/java/com/hatsunama/captionaction/'+name
  s=subprocess.check_output(['git','show',base+':'+p],cwd=root,text=True) if mode=='before' else (root/p).read_text()
  (d/Path(name).name).write_text(s)
 p='app/src/main/java/com/hatsunama/captionaction/ui/home/HomeActivity.kt'
 s=subprocess.check_output(['git','show',base+':'+p],cwd=root,text=True) if mode=='before' else (root/p).read_text()
 start=s.index('btnUse.setOnClickListener {'); launch=s.index('lifecycleScope.launch {',start)
 extracted=body(s,launch)
 (d/'extracted-ready-body.txt').write_text(extracted)
 (d/'ActualReadyCallbackTest.kt').write_text('''package com.hatsunama.captionaction.ui.home
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
class ActualReadyCallbackTest {
 data class Settings(val modelTierId: String = "")
 class SettingsStore {
   val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
   suspend fun update(transform: (Settings) -> Settings) { entered.complete(Unit); resume.await(); transform(Settings()) }
 }
 class App { val settings = SettingsStore() }
 class Dialog { var isShowing = true; fun dismiss() { isShowing = false } }
 val application = App(); fun app() = application
 val dialog = Dialog(); val modelGateDialog = dialog
 private val session = ModelGateSession()
 val tier = com.hatsunama.captionaction.data.ModelTier.BALANCED
 var starts = 0; fun onReady() { starts++ }
 fun gateIsCurrent(): Boolean = session.isActive && dialog.isShowing && modelGateDialog === dialog
 fun start(scope: CoroutineScope) = scope.launch {'''+extracted+'''
 }
 @Test fun dismissedWhileActualSettingsCallbackSuspendsMustNotStart() = runBlocking {
   val job = start(this)
   application.settings.entered.await()
   dialog.dismiss() // onDismiss deliberately delayed, as Android may queue it.
   application.settings.resume.complete(Unit)
   job.join()
   assertEquals("Late production onReady after dismissed dialog", 0, starts)
 }
}
''')
(out/'ContextStub.kt').write_text('package android.content\nimport java.io.File\nclass Context(val filesDir: File)\n')
for mode in ['before', 'after']:
 d=out/mode
 p='app/src/main/java/com/hatsunama/captionaction/ui/home/HomeActivity.kt'
 s=subprocess.check_output(['git','show',base+':'+p],cwd=root,text=True) if mode=='before' else (root/p).read_text()
 extracted=body(s,s.index('result.isSuccess -> {',s.index('btnDownload.setOnClickListener {')))
 (d/'extracted-download-success-body.txt').write_text(extracted)
 source=(d/'ActualReadyCallbackTest.kt').read_text()
 old=(d/'extracted-ready-body.txt').read_text()
 source=source.replace('class ActualReadyCallbackTest {', 'class HomeActivity {').replace(old,extracted)
 source=source.replace(' fun gateIsCurrent()', ''' val cache = object { fun isReadyForAsr(tier: com.hatsunama.captionaction.data.ModelTier) = true }
 fun getString(id: Int) = "ready"
 fun refreshStatus() {}
 fun gateIsCurrent()''').replace(' val cache = object', ' private val cache = object')
 source+='''\nprivate object R { object string { const val model_ready = 1 } }
private object Toast {
 const val LENGTH_SHORT = 0
 fun makeText(context: Any, text: String, duration: Int) = this
 fun show() {}
}
'''
 (d/'DownloadSuccessCallbackTest.kt').write_text(source)
