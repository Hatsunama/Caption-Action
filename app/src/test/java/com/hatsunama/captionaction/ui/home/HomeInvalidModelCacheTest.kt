package com.hatsunama.captionaction.ui.home

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.MlKitTaskBoundary
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
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

/** Baseline-compatible negative inputs: actual Home gate/cache and wrong production-sized bytes. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=[28],application=CaptionActionApp::class,
    shadows=[MlKitTaskBoundary.TranslationFactory::class,MlKitTaskBoundary.IdentifierFactory::class,MlKitTaskBoundary.ModelManager::class],
    instrumentedPackages=["com.google.mlkit.nl.translate.Translation","com.google.mlkit.nl.languageid.LanguageIdentification","com.google.mlkit.common.model.RemoteModelManager"])
class HomeInvalidModelCacheTest {
    private lateinit var controller:ActivityController<HomeActivity>
    private val app get()=RuntimeEnvironment.getApplication() as CaptionActionApp
    private val home get()=controller.get()
    private lateinit var cache:ModelCache
    @Before fun setup() {
        MlKitTaskBoundary.reset()
        MlKitTaskBoundary.downloadedTask=Tasks.forResult(MlKitTranslationEngine.COMMON_SOURCES.map { TranslateRemoteModel.Builder(it).build() }.toSet())
        runBlocking { app.settings.update { it.copy(inputLanguage="en",targetLanguage="en") } }
        controller=Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        cache=ModelCache(app)
        until("initial settings") { home.findViewById<TextView>(R.id.statusText).text.contains("ASR-only") }
    }
    @After fun cleanup() { controller.pause().stop().destroy(); MlKitTaskBoundary.unblock(); shadowOf(Looper.getMainLooper()).idle() }
    private fun until(message:String,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!condition() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(message,condition())
    }
    @Test fun liveThresholdFinalMustNotEnableUse() { rejectGate(R.id.btnStartLive,30_000_000) }
    @Test fun liveExactWrongFinalMustNotEnableUse() { rejectGate(R.id.btnStartLive,32_152_673) }
    @Test fun micThresholdFinalMustNotEnableUse() { rejectGate(R.id.btnMicTranslator,30_000_000) }
    @Test fun micExactWrongFinalMustNotEnableUse() { rejectGate(R.id.btnMicTranslator,32_152_673) }
    private fun rejectGate(button:Int,size:Long) {
        RandomAccessFile(cache.fileFor(ModelTier.BALANCED),"rw").use { it.setLength(size) }
        val before=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()
        home.findViewById<View>(button).performClick()
        val gate=HomeActivity::class.java.getDeclaredField("modelGateDialog").apply { isAccessible=true }.get(home) as AlertDialog
        val gateJobs=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()-before
        until("gate validation finishes") { gateJobs.all { it.isCompleted } }
        assertFalse("invalid model must never enable Use",gate.findViewById<View>(R.id.btnUseAndStart)!!.isEnabled)
        assertTrue("invalid final must not disable recovery",gate.findViewById<View>(R.id.btnDownload)!!.isEnabled)
        assertFalse(cache.isReadyForAsr(ModelTier.BALANCED))
        assertTrue("untrusted legacy file is preserved",cache.fileFor(ModelTier.BALANCED).exists())
    }
    @Test fun invalidFinalMustNeverInvokeReadyCallback() {
        RandomAccessFile(cache.fileFor(ModelTier.BALANCED),"rw").use { it.setLength(32_152_673) }
        val calls=AtomicInteger()
        val before=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()
        HomeActivity::class.java.getDeclaredMethod("showModelGateThenStart",Function0::class.java).apply { isAccessible=true }.invoke(home,{ calls.incrementAndGet(); Unit })
        val gate=HomeActivity::class.java.getDeclaredField("modelGateDialog").apply { isAccessible=true }.get(home) as AlertDialog
        val gateJobs=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()-before
        until("gate validation finishes") { gateJobs.all { it.isCompleted } }
        val beforeUse=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()
        gate.findViewById<View>(R.id.btnUseAndStart)!!.performClick()
        val useJobs=home.lifecycleScope.coroutineContext[Job]!!.children.toSet()-beforeUse
        until("Use work finishes") { useJobs.all { it.isCompleted } }
        assertEquals("invalid bytes must never reach onReady",0,calls.get())
    }
}
