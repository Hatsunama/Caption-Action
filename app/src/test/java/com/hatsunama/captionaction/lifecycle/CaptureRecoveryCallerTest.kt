package com.hatsunama.captionaction.lifecycle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.data.SubtitleFileRecorder
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.MlKitTaskBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledDrain
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledEngine
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledInference
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.FactoryBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.SettingsBoundary
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/** Real owner paths; only hardware/native/model SDK boundaries are controlled. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = CaptionActionApp::class, shadows = [
    FactoryBoundary::class, SettingsBoundary::class,
    CallerPreparationOwnershipTest.ModelAvailabilityBoundary::class, RecoveryCaptureBoundary::class,
    CallerPreparationOwnershipTest.ProjectionManagerBoundary::class,
    CallerPreparationOwnershipTest.ProjectionBoundary::class,
    CallerPreparationOwnershipTest.ImageReaderBoundary::class,
    MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class,
    MlKitTaskBoundary.ModelManager::class
], instrumentedPackages = [
    "com.hatsunama.captionaction.inference.InferenceEngineFactory",
    "com.hatsunama.captionaction.data.SettingsRepository", "com.hatsunama.captionaction.data.ModelCache",
    "com.hatsunama.captionaction.audio.AudioCapture", "com.google.mlkit.nl.translate.Translation",
    "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"
])
class CaptureRecoveryCallerTest {
    @Implements(value = MediaProjection::class, callThroughByDefault = false)
    class QueuedProjectionBoundary {
        @Implementation fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) { callbacks += callback }
        @Implementation fun unregisterCallback(callback: MediaProjection.Callback) {}
        @Implementation fun stop() {}
        @Implementation fun createVirtualDisplay(
            name: String, width: Int, height: Int, densityDpi: Int, flags: Int,
            surface: Surface?, callback: VirtualDisplay.Callback?, handler: Handler?
        ): VirtualDisplay? = null
        companion object { val callbacks = java.util.concurrent.CopyOnWriteArrayList<MediaProjection.Callback>() }
    }

    private val services = mutableListOf<ServiceController<CaptionOverlayService>>()
    private val activities = mutableListOf<ActivityController<MicTranslatorActivity>>()
    private val engines = mutableListOf<ControlledEngine>()
    private val drains = mutableListOf<ControlledDrain>()
    private val inferences = mutableListOf<ControlledInference>()
    private val jobs = mutableListOf<Job>()
    private var startId = 0
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(message, condition())
    }
    @Before fun setup() {
        FactoryBoundary.planned.clear(); RecoveryCaptureBoundary.reset(); MlKitTaskBoundary.reset()
        QueuedProjectionBoundary.callbacks.clear()
        SettingsBoundary.configured = AppSettings(inputLanguage = "zh", targetLanguage = "en", saveSubtitlesToFile = true)
        RuntimeEnvironment.getApplication().getSharedPreferences("mic_translator", Context.MODE_PRIVATE)
            .edit().clear().putString("mic_from", "es").putString("mic_to", "en").commit()
    }
    @After fun cleanup() {
        services.forEach {
            jobs += (field(it.get(), "scope") as CoroutineScope).coroutineContext[Job]!!.children.toList()
            it.destroy()
        }
        activities.forEach { jobs += it.get().lifecycleScope.coroutineContext[Job]!!.children.toList(); it.destroy() }
        engines.forEach { it.allow() }; drains.forEach { it.allow() }; inferences.forEach { it.allow() }
        MlKitTaskBoundary.unblock()
        await("all controlled late work finishes") { MlKitTaskBoundary.unblock(); jobs.all { it.isCompleted } }
    }
    private fun engine(name: String): ControlledEngine = ControlledEngine(name).also {
        engines += it; FactoryBoundary.planned.add(it)
    }
    private fun drain(): ControlledDrain = ControlledDrain(ShortArray(28_000) { 710 }).also {
        drains += it; RecoveryCaptureBoundary.drains.add(it)
    }
    private fun inference(engine: ControlledEngine, text: String, language: String): ControlledInference =
        ControlledInference(CaptionResult(text, language, 1f, 0L, 1000L)).also { inferences += it; engine.inference = it }
    private fun service(): CaptionOverlayService = Robolectric.buildService(CaptionOverlayService::class.java).create()
        .also { services += it }.get()
    private fun start(owner: CaptionOverlayService, expected: ControlledEngine): Job {
        owner.onStartCommand(Intent().apply {
            action = CaptionOverlayService.ACTION_START
            putExtra(CaptionOverlayService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptionOverlayService.EXTRA_RESULT_DATA, Intent("fixture-projection"))
        }, 0, ++startId)
        await("actual live preparation starts") { expected.entered.count == 0L }
        return (field(owner, "preparationJob") as Job).also { jobs += it }
    }
    private fun complete(engine: ControlledEngine, preparation: Job) {
        engine.allow(); await("actual preparation completes") { preparation.isCompleted }
    }
    private fun recorder(owner: CaptionOverlayService): SubtitleFileRecorder = field(owner, "subtitleRecorder") as SubtitleFileRecorder
    private fun mic(expected: ControlledEngine): MicTranslatorActivity {
        val activity = Robolectric.buildActivity(MicTranslatorActivity::class.java).create().also { activities += it }.get()
        await("actual microphone preparation starts") { expected.entered.count == 0L }
        expected.allow(); await("actual microphone preparation is ready") { field(activity, "engineReady") == true }
        return activity
    }
    private fun click(activity: MicTranslatorActivity) { assertTrue(activity.findViewById<MaterialButton>(R.id.btnMic).performClick()) }

    @Test fun captureFailureDuringLiveModelPreparationCleansUpWithoutWaitingForLoad() {
        val owner = service(); val expected = engine("capture-failed-preparation")
        val preparation = start(owner, expected)
        assertNotNull(field(owner, "overlayView")); assertEquals(1L, expected.proceed.count)
        val stopBefore = RecoveryCaptureBoundary.stops.get()
        RecoveryCaptureBoundary.failures.single().invoke()
        assertTrue("failure must immediately cancel actual preparation", preparation.isCancelled)
        assertEquals(true, field(owner, "tearingDown"))
        assertNull(field(owner, "overlayView")); assertNull(field(owner, "mediaProjection"))
        assertNull(field(owner, "engine")); assertNull(field(owner, "translation")); assertNull(field(owner, "asrJob"))
        assertFalse(recorder(owner).isRecording)
        assertEquals(stopBefore + 1, RecoveryCaptureBoundary.stops.get())
        assertEquals(owner.getString(R.string.error_capture), ShadowToast.getTextOfLatestToast())
        assertEquals("native loading owner is retained until it returns", 0, expected.releases.get())
        val toasts = ShadowToast.shownToastCount()
        RecoveryCaptureBoundary.failures.single().invoke()
        assertEquals(toasts, ShadowToast.shownToastCount())
        expected.allow(); await("late native load releases its unpublished engine") { preparation.isCompleted }
        assertEquals(1, expected.releases.get()); assertNull(field(owner, "engine")); assertNull(field(owner, "asrJob"))
        assertTrue(MlKitTaskBoundary.translators.isEmpty())
        assertEquals("Live must never fall back to the microphone", 0, RecoveryCaptureBoundary.microphoneStarts.get())
    }

    @Test fun staleLiveFailureCannotCancelReplacementPreparation() {
        val owner = service(); val old = engine("old-live")
        val oldPreparation = start(owner, old)
        val lateFailure = RecoveryCaptureBoundary.failures.single()
        val fresh = engine("fresh-live"); val newPreparation = start(owner, fresh)
        val stopBefore = RecoveryCaptureBoundary.stops.get(); val toasts = ShadowToast.shownToastCount()
        lateFailure.invoke()
        assertFalse("old failure cannot cancel current preparation", newPreparation.isCancelled)
        assertEquals(false, field(owner, "tearingDown")); assertEquals(stopBefore, RecoveryCaptureBoundary.stops.get())
        assertEquals(toasts, ShadowToast.shownToastCount()); assertNotNull(field(owner, "overlayView"))
        old.allow(); await("retired old preparation finishes") { oldPreparation.isCompleted }
        complete(fresh, newPreparation)
        assertSame(fresh, field(owner, "engine")); assertEquals(1, old.releases.get()); assertEquals(0, fresh.releases.get())
    }

    @Test fun liveFailureCancelsActualPendingTranslationAndPreservesTranscriptAgainstLateCompletion() {
        val owner = service(); val expected = engine("live-pending-mt")
        inference(expected, "蓝色灯笼守护着海港的秘密。", "zh").allow(); drain().allow()
        complete(expected, start(owner, expected))
        await("actual SDK task is pending") { MlKitTaskBoundary.translators.firstOrNull()?.tasks?.size == 1 }
        val client = MlKitTaskBoundary.translators.single()
        val asr = (field(owner, "asrJob") as Job).also { jobs += it }
        val view = field(owner, "overlayView") as View
        val file = File(recorder(owner).currentPath!!); val before = file.readBytes()
        val ownedJobs = (field(owner, "scope") as CoroutineScope).coroutineContext[Job]!!.children.toList()
        jobs += ownedJobs
        RecoveryCaptureBoundary.failures.single().invoke()
        assertTrue(asr.isCancelled); assertNull(field(owner, "overlayView")); assertNull(field(owner, "translation"))
        assertFalse(recorder(owner).isRecording); assertEquals(1, expected.releases.get())
        assertEquals(0, client.closes.get()); assertFalse(client.tasks.single().task.isComplete)
        val oldText = view.findViewById<TextView>(R.id.captionPrimary).text.toString()
        client.complete("Late abandoned result must never be appended")
        await("late actual SDK completion releases its owned client") { client.closes.get() == 1 && ownedJobs.all { it.isCompleted } }
        assertArrayEquals(before, file.readBytes()); assertEquals(oldText, view.findViewById<TextView>(R.id.captionPrimary).text.toString())
        assertEquals(0, RecoveryCaptureBoundary.microphoneStarts.get())
    }

    @Test fun micFailureStopsListeningAndLateAsrCannotPoisonRestart() {
        val expected = engine("mic-held-asr"); val activity = mic(expected)
        val oldInference = inference(expected, "La linterna azul protege nuestro puerto", "es")
        drain().allow(); click(activity)
        await("actual native ASR is held") { oldInference.entered.count == 0L }
        val oldJob = (field(activity, "asrJob") as Job).also { jobs += it }
        val oldFailure = RecoveryCaptureBoundary.failures.single()
        oldFailure.invoke()
        assertTrue(oldJob.isCancelled); assertEquals(false, field(activity, "micOn")); assertNull(field(activity, "asrJob"))
        assertEquals(activity.getString(R.string.error_mic_capture), ShadowToast.getTextOfLatestToast())
        assertEquals(activity.getString(R.string.mic_translator_mic_off), activity.findViewById<MaterialButton>(R.id.btnMic).contentDescription)
        val visible = activity.findViewById<TextView>(R.id.captionText).text.toString()
        oldInference.allow(); await("late old native ASR exits") { oldJob.isCompleted }
        assertEquals(visible, activity.findViewById<TextView>(R.id.captionText).text.toString())
        assertTrue((field(activity, "lines") as List<*>).isEmpty())
        val held = drain(); click(activity)
        await("restarted actual Mic loop reaches FIFO") { held.entered.count == 0L }
        val freshJob = (field(activity, "asrJob") as Job).also { jobs += it }
        val stopsBefore = RecoveryCaptureBoundary.stops.get(); val toasts = ShadowToast.shownToastCount()
        oldFailure.invoke()
        assertFalse(freshJob.isCancelled); assertEquals(true, field(activity, "micOn"))
        assertEquals(stopsBefore, RecoveryCaptureBoundary.stops.get()); assertEquals(toasts, ShadowToast.shownToastCount())
    }

    @Test fun micFailureRetainsPendingMtUntilSdkCompletionAndKeepsPublishedSource() {
        val expected = engine("mic-pending-mt"); val activity = mic(expected)
        val phrase = "La linterna azul protege nuestro puerto"
        inference(expected, phrase, "es").allow(); drain().allow(); click(activity)
        await("actual microphone translation is pending") { MlKitTaskBoundary.translators.firstOrNull()?.tasks?.size == 1 }
        val client = MlKitTaskBoundary.translators.single()
        val asr = (field(activity, "asrJob") as Job).also { jobs += it }
        assertEquals(listOf(phrase), field(activity, "lines"))
        RecoveryCaptureBoundary.failures.single().invoke()
        assertTrue(asr.isCancelled); assertEquals(false, field(activity, "micOn"))
        assertEquals(listOf(phrase), field(activity, "lines")); assertEquals(0, client.closes.get())
        client.complete("Retired translation must not replace the source")
        await("actual canceled awaiter finishes") { asr.isCompleted }
        assertEquals(listOf(phrase), field(activity, "lines"))
        // Mic stop retains its translation engine for retry; Activity destroy retires it.
        assertEquals(0, client.closes.get())
    }
    @Test
    @Config(shadows = [QueuedProjectionBoundary::class])
    fun queuedOldProjectionStopCannotTearDownAReplacementStart() {
        val owner = service(); val old = engine("old-projection")
        val oldPreparation = start(owner, old)
        val callback = QueuedProjectionBoundary.callbacks.single()
        callback.onStop() // The actual callback queues its failure on paused Main.
        assertEquals(false, field(owner, "tearingDown"))
        val fresh = engine("replacement-projection")
        owner.onStartCommand(Intent().apply {
            action = CaptionOverlayService.ACTION_START
            putExtra(CaptionOverlayService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptionOverlayService.EXTRA_RESULT_DATA, Intent("fresh-fixture-projection"))
        }, 0, ++startId)
        val newPreparation = (field(owner, "preparationJob") as Job).also { jobs += it }
        val freshProjection = field(owner, "mediaProjection")
        assertNotNull(freshProjection); assertTrue(oldPreparation.isCancelled)
        val stopsBefore = RecoveryCaptureBoundary.stops.get(); val toastCount = ShadowToast.shownToastCount()
        shadowOf(Looper.getMainLooper()).idle() // Deliver old queued failure after replacement.
        assertEquals("old projection failure cannot retire the replacement", false, field(owner, "tearingDown"))
        assertFalse(newPreparation.isCancelled); assertSame(freshProjection, field(owner, "mediaProjection"))
        assertNotNull(field(owner, "overlayView")); assertEquals(stopsBefore, RecoveryCaptureBoundary.stops.get())
        assertEquals(toastCount, ShadowToast.shownToastCount())
        old.allow(); await("old replaced preparation retires") { oldPreparation.isCompleted }
        complete(fresh, newPreparation)
        assertSame(fresh, field(owner, "engine")); assertEquals(1, old.releases.get()); assertEquals(0, fresh.releases.get())
    }

    @Test
    @Config(shadows = [QueuedProjectionBoundary::class])
    fun currentProjectionStopStillFailsItsSessionOnlyOnce() {
        val owner = service(); val expected = engine("current-projection")
        val preparation = start(owner, expected)
        val callback = QueuedProjectionBoundary.callbacks.single()
        val toastsBefore = ShadowToast.shownToastCount()
        callback.onStop(); callback.onStop() // Both were queued while this owner was live.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, field(owner, "tearingDown")); assertTrue(preparation.isCancelled)
        assertNull(field(owner, "mediaProjection")); assertNull(field(owner, "overlayView"))
        assertEquals(owner.getString(R.string.error_projection_stopped), ShadowToast.getTextOfLatestToast())
        assertEquals("queued duplicate system callbacks must not repeat error navigation", toastsBefore + 1, ShadowToast.shownToastCount())
        expected.allow(); await("retired preparation finishes") { preparation.isCompleted }
        assertEquals(1, expected.releases.get()); assertEquals(0, RecoveryCaptureBoundary.microphoneStarts.get())
    }

}
