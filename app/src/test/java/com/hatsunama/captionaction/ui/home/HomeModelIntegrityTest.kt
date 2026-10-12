package com.hatsunama.captionaction.ui.home

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.hatsunama.captionaction.service.ModelDownloadLocks
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelFileOperations
import com.hatsunama.captionaction.data.ModelIdentity
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.MlKitTaskBoundary
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Home gate and real cache hashing. Only small artifact identity and SDK Tasks are fixtures. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = CaptionActionApp::class,
    shadows = [MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class, MlKitTaskBoundary.ModelManager::class],
    instrumentedPackages = ["com.google.mlkit.nl.translate.Translation", "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"])
class HomeModelIntegrityTest {
    private lateinit var controller: ActivityController<HomeActivity>
    private val app get() = RuntimeEnvironment.getApplication() as CaptionActionApp
    private val home get() = controller.get()
    private val bytes = ByteArray(8193) { (it * 17).toByte() }
    private val identity = ModelIdentity(bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private val releases = mutableListOf<CountDownLatch>()
    private val callbacks = AtomicInteger()
    private var destroyed = false
    private var gateWork: Job? = null
    @Before fun setup() {
        MlKitTaskBoundary.reset()
        MlKitTaskBoundary.downloadedTask = Tasks.forResult(MlKitTranslationEngine.COMMON_SOURCES.map { TranslateRemoteModel.Builder(it).build() }.toSet())
        runBlocking { app.settings.update { it.copy(inputLanguage="en", targetLanguage="en") } }
        controller=Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        until("initial collector must finish") { home.findViewById<TextView>(R.id.statusText).text.contains("ASR-only") }
        // Let the empty production-cache verification finish before installing a fixture cache.
        shadowOf(Looper.getMainLooper()).idle()
    }
    @After fun cleanup() {
        releases.forEach { it.countDown() }
        if (::controller.isInitialized && !destroyed) controller.pause().stop().destroy()
        MlKitTaskBoundary.unblock(); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun until(message:String, condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!condition() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(message,condition())
    }
    private fun install(operations: ModelFileOperations = ModelFileOperations()):ModelCache {
        val cache=ModelCache(app,{identity},operations)
        HomeActivity::class.java.getDeclaredField("cache").apply { isAccessible=true }.set(home,cache)
        return cache
    }
    private fun gate(trackVerification: Boolean = false):AlertDialog {
        // For completion assertions, hold the real path lock until the actual gate child is captured.
        // Otherwise a fast missing-file check can complete and disappear before enumeration.
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val lockOwner = if (trackVerification) CoroutineScope(Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
            ModelDownloadLocks.withFile(ModelCache(app).fileFor(ModelTier.BALANCED)) {
                entered.complete(Unit)
                release.await()
            }
        } else null
        try {
            if (trackVerification) until("fixture holds actual model lock") { entered.isCompleted }
            val before=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()
            HomeActivity::class.java.getDeclaredMethod("showModelGateThenStart",Function0::class.java).apply { isAccessible=true }.invoke(home,{ callbacks.incrementAndGet(); Unit })
            gateWork=(home.lifecycleScope.coroutineContext[Job]!!.children.toSet()-before).singleOrNull()
            if (trackVerification) assertTrue("actual gate verification started and awaits the held model lock", gateWork?.isActive == true)
            return HomeActivity::class.java.getDeclaredField("modelGateDialog").apply { isAccessible=true }.get(home) as AlertDialog
        } finally {
            release.complete(Unit)
            lockOwner?.cancel()
        }
    }
    private fun use(gate:AlertDialog)=gate.findViewById<View>(R.id.btnUseAndStart)!!
    private fun download(gate:AlertDialog)=gate.findViewById<View>(R.id.btnDownload)!!
    private fun blockedOperations(entered:CountDownLatch, release:CountDownLatch)=object:ModelFileOperations() {
        override fun openRead(file:File):InputStream {
            val real=super.openRead(file)
            return object:java.io.FilterInputStream(real) {
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    entered.countDown(); check(release.await(8,TimeUnit.SECONDS)); return super.read(b,off,len)
                }
            }
        }
    }
    @Test fun actualHashBlocksUseButNotMainAndEnablesOnlyAfterVerification() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1).also { releases+=it }
        val cache=install(blockedOperations(entered,release)); cache.fileFor(ModelTier.BALANCED).writeBytes(bytes)
        val gate=gate(); assertFalse(use(gate).isEnabled)
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        var mainRan=false; android.os.Handler(Looper.getMainLooper()).post { mainRan=true }; shadowOf(Looper.getMainLooper()).idle()
        assertTrue(mainRan); assertFalse(use(gate).isEnabled); assertTrue(download(gate).isEnabled)
        use(gate).performClick(); assertEquals(0,callbacks.get())
        release.countDown(); until("Use enables only after actual hash") { use(gate).isEnabled }
        assertFalse(download(gate).isEnabled)
        use(gate).performClick(); until("verified gate invokes ready once") { callbacks.get()==1 }
    }
    @Test fun invalidExactSizeCacheLeavesRecoveryEnabledAndNeverCallsReady() {
        val cache=install(); cache.fileFor(ModelTier.BALANCED).writeBytes(ByteArray(bytes.size))
        val gate=gate(trackVerification = true); until("verification must finish") { gateWork?.isCompleted == true }
        shadowOf(Looper.getMainLooper()).idle(); assertFalse(use(gate).isEnabled); use(gate).performClick(); assertEquals(0,callbacks.get())
        assertTrue(cache.fileFor(ModelTier.BALANCED).exists())
    }
    @Test fun oldBlockedGateCannotReviveAfterCloseAndReplacement() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1).also { releases+=it }
        val cache=install(blockedOperations(entered,release)); cache.fileFor(ModelTier.BALANCED).writeBytes(bytes)
        val old=gate(); assertTrue(entered.await(5,TimeUnit.SECONDS)); val replacement=gate()
        assertFalse(old.isShowing); assertFalse(use(replacement).isEnabled)
        release.countDown(); until("replacement independently verifies") { use(replacement).isEnabled }
        assertFalse(use(old).isEnabled); use(old).performClick(); assertEquals(0,callbacks.get())
        use(replacement).performClick(); until("replacement invokes ready") { callbacks.get()==1 }
    }
    @Test fun destroyedHomeCannotRunReadyAfterBlockedVerification() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1).also { releases+=it }
        val cache=install(blockedOperations(entered,release)); cache.fileFor(ModelTier.BALANCED).writeBytes(bytes)
        val gate=gate(); assertTrue(entered.await(5,TimeUnit.SECONDS)); controller.pause().stop().destroy(); destroyed=true
        release.countDown(); shadowOf(Looper.getMainLooper()).idle(); assertFalse(gate.isShowing); assertFalse(use(gate).isEnabled); assertEquals(0,callbacks.get())
    }
    @Test fun warmSnapshotCannotBypassNewGateVerification() {
        val cache=install(); val f=cache.fileFor(ModelTier.BALANCED); f.writeBytes(bytes); assertTrue(runBlocking { cache.verify(ModelTier.BALANCED) })
        val time=java.nio.file.Files.getLastModifiedTime(f.toPath()); f.writeBytes(ByteArray(bytes.size)); java.nio.file.Files.setLastModifiedTime(f.toPath(),time)
        val gate=gate(trackVerification = true); assertFalse(use(gate).isEnabled)
        until("forced gate verification invalidates warm trust") { gateWork?.isCompleted == true }; assertFalse(cache.isPresent(ModelTier.BALANCED))
        use(gate).performClick(); assertEquals(0,callbacks.get()); assertTrue(download(gate).isEnabled)
    }
    @Test fun useRechecksChangedBytesBeforeReadyCallback() {
        val cache=install(); val f=cache.fileFor(ModelTier.BALANCED); f.writeBytes(bytes)
        val gate=gate(); until("valid gate ready") { use(gate).isEnabled }
        val time=java.nio.file.Files.getLastModifiedTime(f.toPath()); f.writeBytes(ByteArray(bytes.size)); java.nio.file.Files.setLastModifiedTime(f.toPath(),time)
        use(gate).performClick(); until("Use's recheck offers recovery") { download(gate).isEnabled }
        assertFalse(use(gate).isEnabled); assertEquals(0,callbacks.get())
    }
    @Test fun completeValidPartialPromotesThroughRealGateDespiteNoFreeDownloadSpace() {
        val cache=install()
        val rootField=ModelCache::class.java.getDeclaredField("root").apply { isAccessible=true }
        val root=rootField.get(cache) as File
        rootField.set(cache,object:File(root.path) { override fun getUsableSpace():Long=0L })
        assertEquals(0,cache.freeBytes())
        cache.partFileFor(ModelTier.BALANCED).writeBytes(bytes)
        val gate=gate(trackVerification = true); until("initial gate verification finishes") { gateWork?.isCompleted==true }
        assertTrue(download(gate).isEnabled)
        download(gate).performClick()
        until("complete valid partial needs no download space and must reach onReady") { callbacks.get()==1 }
        assertArrayEquals(bytes,cache.fileFor(ModelTier.BALANCED).readBytes())
        assertFalse(cache.partFileFor(ModelTier.BALANCED).exists())
    }
    @Test fun overlappingStatusVerificationCannotPreventValidUseRecheck() {
        val entered=CountDownLatch(1); val release=CountDownLatch(1).also { releases+=it }
        val blocking=java.util.concurrent.atomic.AtomicBoolean(false)
        val operations=object:ModelFileOperations() {
            override fun openRead(file:File):InputStream {
                val real=super.openRead(file)
                return object:java.io.FilterInputStream(real) {
                    override fun read(b:ByteArray,off:Int,len:Int):Int {
                        if(blocking.get()) { entered.countDown(); check(release.await(8,TimeUnit.SECONDS)) }
                        return super.read(b,off,len)
                    }
                }
            }
        }
        val cache=install(operations); cache.fileFor(ModelTier.BALANCED).writeBytes(bytes)
        val gate=gate(); until("initial gate ready") { use(gate).isEnabled }
        blocking.set(true)
        runBlocking { app.settings.update { it.copy(overlayX=it.overlayX+1) } }
        until("actual settings collector starts status verification") { entered.count==0L }
        use(gate).performClick()
        release.countDown()
        until("Use must await/recheck rather than reject transient readiness") { callbacks.get()==1 }
    }

    @Test fun insufficientSpaceFromRealDownloaderUsesExistingStorageErrorAndAllowsRetry() {
        val cache=install()
        val rootField=ModelCache::class.java.getDeclaredField("root").apply { isAccessible=true }
        val root=rootField.get(cache) as File
        rootField.set(cache,object:File(root.path) { override fun getUsableSpace():Long=0L })
        val gate=gate(trackVerification = true); until("initial missing-model verification finishes") { gateWork?.isCompleted==true }
        org.robolectric.shadows.ShadowToast.reset()
        download(gate).performClick()
        val job=HomeActivity::class.java.getDeclaredField("downloadJob").apply { isAccessible=true }.get(home) as Job
        until("actual downloader reports insufficient space") { job.isCompleted }
        assertEquals(app.getString(R.string.error_storage),org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue(download(gate).isEnabled); assertFalse(use(gate).isEnabled); assertEquals(0,callbacks.get())
        assertFalse(cache.partFileFor(ModelTier.BALANCED).exists()); assertFalse(cache.fileFor(ModelTier.BALANCED).exists())
    }

    @Test fun laterSuccessfulStatusVerificationCanRecoverGateAfterTransientReadFailure() {
        val fail=java.util.concurrent.atomic.AtomicBoolean(true)
        val cache=install(object:ModelFileOperations() { override fun openRead(file:File):InputStream {
            if(fail.get()) throw java.io.IOException("transient read failure")
            return super.openRead(file)
        } })
        cache.fileFor(ModelTier.BALANCED).writeBytes(bytes)
        val gate=gate(trackVerification = true); until("gate's first forced check must fail") { gateWork?.isCompleted==true }
        assertFalse(use(gate).isEnabled); assertTrue(download(gate).isEnabled); assertFalse(cache.isPresent(ModelTier.BALANCED))
        fail.set(false)
        runBlocking { app.settings.update { it.copy(overlayX=it.overlayX+1) } }
        until("actual later status verification succeeds") { cache.isPresent(ModelTier.BALANCED) }
        assertFalse(use(gate).isEnabled)
        download(gate).performClick()
        until("Download must recover this gate by verifying the existing valid file") { callbacks.get()==1 }
        assertArrayEquals(bytes,cache.fileFor(ModelTier.BALANCED).readBytes())
        assertFalse(cache.partFileFor(ModelTier.BALANCED).exists())
    }

}
