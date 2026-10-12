package com.hatsunama.captionaction.lifecycle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.audio.AudioCapture
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.data.SettingsRepository
import com.hatsunama.captionaction.data.SubtitleFileRecorder
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.EnsureResult
import com.hatsunama.captionaction.inference.InferenceEngine
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector

/**
 * Actual service START/STOP and activity create/destroy/preparation bodies. Only external
 * settings/model-availability, inference factory, capture/projection, and ML Kit boundaries
 * are controlled. No production owner method is shadowed or replaced, and no native model,
 * real microphone, projection session, network operation, or model download is performed.
 *
 * load() deliberately blocks a real IO worker and ignores cancellation, like a native load.
 * The paused Main looper and captured actual preparation Job determine completion. Timeouts
 * only diagnose a hung fixture; neither sleeps nor elapsed time establish race ordering.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    sdk = [35],
    application = CaptionActionApp::class,
    shadows = [
        CallerPreparationOwnershipTest.FactoryBoundary::class,
        CallerPreparationOwnershipTest.SettingsBoundary::class,
        CallerPreparationOwnershipTest.ModelAvailabilityBoundary::class,
        CallerPreparationOwnershipTest.CaptureBoundary::class,
        CallerPreparationOwnershipTest.TranslationBoundary::class,
        CallerPreparationOwnershipTest.ProjectionManagerBoundary::class,
        CallerPreparationOwnershipTest.ProjectionBoundary::class,
        CallerPreparationOwnershipTest.ImageReaderBoundary::class,
        CallerPreparationOwnershipTest.RecorderAppendBoundary::class
    ],
    instrumentedPackages = [
        "com.hatsunama.captionaction.inference.InferenceEngineFactory",
        "com.hatsunama.captionaction.inference.MlKitTranslationEngine",
        "com.hatsunama.captionaction.data.SettingsRepository",
        "com.hatsunama.captionaction.data.ModelCache",
        "com.hatsunama.captionaction.data.SubtitleFileRecorder",
        "com.hatsunama.captionaction.audio.AudioCapture"
    ]
)
class CallerPreparationOwnershipTest {
    class ControlledEngine(override val name: String) : InferenceEngine {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val returning = CountDownLatch(1)
        val releases = AtomicInteger()
        var succeeds = true
        var inference: ControlledInference? = null

        override suspend fun load(modelFile: File): Boolean {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS)) { "$name load was never released" }
            returning.countDown()
            return succeeds
        }

        override suspend fun transcribeWindow(
            pcm16le: ShortArray, sampleRateHz: Int, forceFlush: Boolean
        ): CaptionResult? {
            val operation = inference ?: error("No controlled inference was planned")
            check(pcm16le.isNotEmpty() && pcm16le.any { it != 0.toShort() })
            operation.entered.countDown()
            check(operation.proceed.await(10, TimeUnit.SECONDS)) { "$name inference was never released" }
            // Intentionally no cancellable suspension or active check: native work can return
            // after Job.cancel(), so the actual caller must reject this retired completion.
            return operation.result
        }

        override fun release() { releases.incrementAndGet() }
        fun allow() { proceed.countDown() }
    }

    @Implements(InferenceEngineFactory::class)
    class FactoryBoundary {
        @Implementation fun create(context: Context, tier: ModelTier, targetLanguage: String): InferenceEngine =
            planned.remove()

        companion object { val planned = LinkedBlockingQueue<ControlledEngine>() }
    }

    @Implements(SettingsRepository::class)
    class SettingsBoundary {
        @Implementation fun current(continuation: Continuation<AppSettings>): Any = configured
        companion object { @Volatile var configured = AppSettings() }
    }

    @Implements(ModelCache::class)
    class ModelAvailabilityBoundary {
        @Implementation fun isReadyForAsr(tier: ModelTier): Boolean = true
        @Implementation fun fileFor(tier: ModelTier): File = File("/fixture-only/no-model-bytes")

        @Implementation
        fun verify(tier: ModelTier, continuation: Continuation<Boolean>): Any? {
            val operation: suspend () -> Boolean = { withContext(Dispatchers.IO) { true } }
            return operation.startCoroutineUninterceptedOrReturn(continuation)
        }

        @Implementation
        fun <T> withVerifiedModel(
            tier: ModelTier,
            block: suspend (File) -> T,
            continuation: Continuation<T?>
        ): Any? {
            // Preserve the real API's structured IO child and cancellable Main return.
            // Never runBlocking, launch an independent Job, or invoke a blocking fake
            // native load on Main: existing ownership assertions depend on this lifetime.
            val operation: suspend () -> T? = {
                withContext(Dispatchers.IO) { block(fileFor(tier)) }
            }
            return operation.startCoroutineUninterceptedOrReturn(continuation)
        }
    }

    class ControlledInference(val result: CaptionResult) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        fun allow() { proceed.countDown() }
    }

    class ControlledDrain(val pcm: ShortArray? = null) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        fun allow() { proceed.countDown() }
    }

    @Implements(value = AudioCapture::class, callThroughByDefault = false)
    class CaptureBoundary {
        @Implementation fun __constructor__(context: Context) {}
        @Implementation fun hasRecordAudioPermission(): Boolean = true
        @Implementation fun startPlaybackCapture(projection: MediaProjection): Boolean = true
        @Implementation fun startMicrophoneCapture(): Boolean = true
        @Implementation fun drainFifoWindow(targetSamples: Int, continuation: Continuation<ShortArray?>): Any? =
            drainToNewestWindow(targetSamples, continuation)
        @Implementation fun getUsingPlaybackCapture(): Boolean = true
        @Implementation fun startPump(scope: CoroutineScope) {}
        @Implementation fun startPump(scope: CoroutineScope, onFailure: () -> Unit) {}
        @Implementation fun stop() {}
        @Implementation fun overrunCount(): Long = 0L
        @Implementation fun chunkCount(): Long = 0L
        @Implementation fun queueDepth(): Int = 0
        @Implementation fun lastRms(): Float = 0f
        @Implementation fun drainToNewestWindow(targetSamples: Int, continuation: Continuation<ShortArray?>): Any? {
            (nextDrain.getAndSet(null) ?: extraDrains.poll())?.let { operation ->
                operation.entered.countDown()
                check(operation.proceed.await(10, TimeUnit.SECONDS)) { "Controlled capture drain was never released" }
                return operation.pcm
            }
            return null
        }
        companion object {
            val nextDrain = AtomicReference<ControlledDrain?>()
            val extraDrains = LinkedBlockingQueue<ControlledDrain>()
        }
    }

    @Implements(value = MlKitTranslationEngine::class, callThroughByDefault = false)
    class TranslationBoundary {
        @Implementation fun __constructor__(context: Context) { constructions.incrementAndGet() }
        @Implementation fun ensureModels(
            targetLanguage: String,
            extraSources: Set<String>,
            onStatus: ((String) -> Unit)?,
            continuation: Continuation<EnsureResult>
        ): Any = EnsureResult.Ready
        @Implementation fun release() {}
        @Implementation fun applyPolicy(result: CaptionResult, settings: AppSettings, continuation: Continuation<CaptionResult>): Any {
            failureMessage?.let { throw IllegalStateException(it) }
            translationGate?.let { check(it.await(10, TimeUnit.SECONDS)) }
            return result.copy(translatedText = translatedOutput)
        }
        @Implementation fun translateExplicit(text: String, source: String, target: String, continuation: Continuation<String?>): Any? {
            failureMessage?.let { throw IllegalStateException(it) }
            return translatedOutput
        }
        companion object {
            @Volatile var failureMessage: String? = null
            val constructions = AtomicInteger()
            @Volatile var translatedOutput: String? = null
            @Volatile var translationGate: CountDownLatch? = null
        }
    }

    @Implements(MediaProjectionManager::class)
    class ProjectionManagerBoundary {
        @Implementation fun getMediaProjection(resultCode: Int, resultData: Intent): MediaProjection =
            Shadow.newInstanceOf(MediaProjection::class.java)
    }

    @Implements(value = MediaProjection::class, callThroughByDefault = false)
    class ProjectionBoundary {
        @Implementation fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) {}
        @Implementation fun unregisterCallback(callback: MediaProjection.Callback) {}
        @Implementation fun stop() {}
        // Keep-alive is deliberately unavailable. Production permits a successful audio start.
        @Implementation fun createVirtualDisplay(
            name: String, width: Int, height: Int, densityDpi: Int, flags: Int,
            surface: Surface?, callback: VirtualDisplay.Callback?, handler: Handler?
        ): VirtualDisplay? = null
    }

    @Implements(value = ImageReader::class, callThroughByDefault = false)
    class ImageReaderBoundary {
        @Implementation fun getSurface(): Surface? = null
        @Implementation fun close() {}
        companion object {
            @JvmStatic @Implementation fun newInstance(width: Int, height: Int, format: Int, maxImages: Int): ImageReader =
                Shadow.newInstanceOf(ImageReader::class.java)
        }
    }

    class ControlledSessionStart {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        @Volatile var createdPath: String? = null
        fun allow() { proceed.countDown() }
    }

    class ControlledAppend(val marker: String) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        fun allow() { proceed.countDown() }
    }

    /**
     * Observe/control only entry to the recorder's append boundary, then call its real body.
     * The pause occurs before its lock, allowing actual startSession to replace the file.
     * Both entry points are intentional: the original unguarded caller must fail the same
     * test that the expected-session append API passes. All bytes are written by real code.
     */
    @Implements(value = SubtitleFileRecorder::class, isInAndroidSdk = false)
    class RecorderAppendBoundary {
        @RealObject lateinit var recorder: SubtitleFileRecorder
        private fun pauseIfPlanned(primary: String) {
            val operation = nextAppend.get() ?: return
            if (!primary.contains(operation.marker) || !nextAppend.compareAndSet(operation, null)) return
            operation.entered.countDown()
            check(operation.proceed.await(10, TimeUnit.SECONDS)) { "Controlled recorder append was never released" }
        }
        @Implementation fun startSession(targetLanguage: String, dualSubtitles: Boolean): String? {
            val operation = nextStart.getAndSet(null)
            operation?.let {
                it.entered.countDown()
                check(it.proceed.await(10, TimeUnit.SECONDS)) { "Controlled recorder start was never released" }
            }
            val path = reflector(RecorderStartDirect::class.java, recorder).startSession(targetLanguage, dualSubtitles)
            operation?.createdPath = path
            return path
        }
        @Implementation fun appendCaption(primary: String, secondary: String?) {
            pauseIfPlanned(primary)
            reflector(RecorderOriginalDirect::class.java, recorder).appendCaption(primary, secondary)
        }
        @Implementation fun appendCaptionForSession(expectedPath: String, primary: String, secondary: String?) {
            pauseIfPlanned(primary)
            reflector(RecorderSessionDirect::class.java, recorder)
                .appendCaptionForSession(expectedPath, primary, secondary)
        }
        companion object {
            val nextAppend = AtomicReference<ControlledAppend?>()
            val nextStart = AtomicReference<ControlledSessionStart?>()
        }
    }

    @ForType(SubtitleFileRecorder::class)
    interface RecorderStartDirect {
        @Direct fun startSession(targetLanguage: String, dualSubtitles: Boolean): String?
    }
    @ForType(SubtitleFileRecorder::class)
    interface RecorderOriginalDirect {
        @Direct fun appendCaption(primary: String, secondary: String?)
    }
    @ForType(SubtitleFileRecorder::class)
    interface RecorderSessionDirect {
        @Direct fun appendCaptionForSession(expectedPath: String, primary: String, secondary: String?)
    }

    private class ServiceOwner(val controller: ServiceController<CaptionOverlayService>) {
        val value: CaptionOverlayService get() = controller.get()
        var destroyed = false
        fun destroy() { if (!destroyed) { destroyed = true; controller.destroy() } }
    }
    private class MicOwner(val controller: ActivityController<MicTranslatorActivity>) {
        val value: MicTranslatorActivity get() = controller.get()
        var destroyed = false
        fun destroy() { if (!destroyed) { destroyed = true; controller.destroy() } }
    }

    private val engines = mutableListOf<ControlledEngine>()
    private val drains = mutableListOf<ControlledDrain>()
    private val inferences = mutableListOf<ControlledInference>()
    private val appends = mutableListOf<ControlledAppend>()
    private val recordingStarts = mutableListOf<ControlledSessionStart>()
    private val services = mutableListOf<ServiceOwner>()
    private val microphones = mutableListOf<MicOwner>()
    private val preparations = mutableListOf<Job>()
    private val consumers = mutableListOf<Job>()
    private var nextStartId = 1

    @Before fun resetBoundaries() {
        FactoryBoundary.planned.clear()
        CaptureBoundary.nextDrain.set(null)
        CaptureBoundary.extraDrains.clear()
        SettingsBoundary.configured = AppSettings()
        RecorderAppendBoundary.nextAppend.set(null)
        RecorderAppendBoundary.nextStart.set(null)
        TranslationBoundary.constructions.set(0)
        TranslationBoundary.failureMessage = null
        TranslationBoundary.translatedOutput = null
        TranslationBoundary.translationGate = null
    }

    @After fun releaseFixture() {
        TranslationBoundary.translationGate?.countDown()
        engines.forEach { it.allow() }
        drains.forEach { it.allow() }
        inferences.forEach { it.allow() }
        appends.forEach { it.allow() }
        recordingStarts.forEach { it.allow() }
        services.forEach { owner ->
            (field(owner.value, "asrJob") as Job?)?.let { consumers += it }
            owner.destroy()
        }
        microphones.forEach { it.destroy() }
        drainMainUntil("all preparation and consumer jobs terminate in cleanup") {
            preparations.all { it.isCompleted } && consumers.all { it.isCompleted }
        }
    }

    private fun plan(name: String): ControlledEngine = ControlledEngine(name).also {
        engines += it
        FactoryBoundary.planned.add(it)
    }

    private fun service(): ServiceOwner =
        ServiceOwner(Robolectric.buildService(CaptionOverlayService::class.java).create()).also { services += it }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun engine(owner: Any): InferenceEngine? = field(owner, "engine") as InferenceEngine?

    private fun scope(owner: CaptionOverlayService): CoroutineScope = field(owner, "scope") as CoroutineScope

    private fun activePreparations(scope: CoroutineScope): Set<Job> = scope.coroutineContext[Job]!!.children.toSet()

    private fun await(latch: CountDownLatch, message: String) {
        assertTrue(message, latch.await(10, TimeUnit.SECONDS))
    }

    private fun drainMainUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(message, condition())
    }

    private fun awaitWithoutMain(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(message, condition())
    }

    private fun start(owner: ServiceOwner, expected: ControlledEngine): Job {
        val before = activePreparations(scope(owner.value))
        val intent = Intent().apply {
            action = CaptionOverlayService.ACTION_START
            putExtra(CaptionOverlayService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptionOverlayService.EXTRA_RESULT_DATA, Intent("fixture-projection-grant"))
        }
        assertEquals(android.app.Service.START_NOT_STICKY, owner.value.onStartCommand(intent, 0, nextStartId++))
        drainMainUntil("${expected.name} must enter actual service preparation") { expected.entered.count == 0L }
        val job = (activePreparations(scope(owner.value)) - before).single()
        preparations += job
        return job
    }

    private fun createMic(expected: ControlledEngine): Pair<MicOwner, Job> {
        val owner = MicOwner(Robolectric.buildActivity(MicTranslatorActivity::class.java).create()).also {
            microphones += it
        }
        drainMainUntil("${expected.name} must enter actual activity preparation") { expected.entered.count == 0L }
        val job = activePreparations(owner.value.lifecycleScope).single()
        preparations += job
        return owner to job
    }

    private fun complete(engine: ControlledEngine, preparation: Job) {
        engine.allow()
        drainMainUntil("${engine.name} preparation must finish") { preparation.isCompleted }
    }

    /** Observe that IO completed while the actual caller continuation is still queued on Main. */
    private fun queueMainReturn(engine: ControlledEngine, preparation: Job) {
        assertTrue("load must be a child of the actual caller", preparation.children.any())
        engine.allow()
        await(engine.returning, "${engine.name} must return from the blocking IO load")
        awaitWithoutMain("IO child must finish before lifecycle teardown") { preparation.children.none() }
        assertFalse("Main remains paused before ownership publication", preparation.isCompleted)
    }

    private fun stop(owner: ServiceOwner) {
        owner.value.onStartCommand(Intent().setAction(CaptionOverlayService.ACTION_STOP), 0, nextStartId++)
    }

    @Test fun overlappingStartsCannotPublishOldCompletionOverNewEngine() {
        val owner = service()
        val old = plan("old")
        val oldJob = start(owner, old)
        val fresh = plan("fresh")
        val freshJob = start(owner, fresh)
        complete(fresh, freshJob)
        assertSame("fresh start must publish", fresh, engine(owner.value))
        complete(old, oldJob)
        assertSame("late old preparation must never overwrite fresh ownership", fresh, engine(owner.value))
        assertEquals("unpublished old engine must be released exactly once", 1, old.releases.get())
        assertEquals(0, fresh.releases.get())
        assertEquals("stale preparation must not create translation resources", 1, TranslationBoundary.constructions.get())
        stop(owner)
        owner.destroy()
        assertEquals(1, old.releases.get())
        assertEquals("STOP plus destroy must release the published engine once", 1, fresh.releases.get())
    }

    @Test fun newStartCancelsPriorAsrBeforeReplacementLoadCompletes() {
        val owner = service()
        val drain = ControlledDrain().also { drains += it }
        CaptureBoundary.nextDrain.set(drain)
        val old = plan("old-asr-owner")
        complete(old, start(owner, old))
        await(drain.entered, "the actual prior ASR consumer must enter capture drain")
        val oldAsr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        assertFalse("the old consumer must still be blocked in actual runAsrConsumer", oldAsr.isCompleted)
        val fresh = plan("replacement-with-blocked-load")
        val freshPreparation = start(owner, fresh)
        try {
            assertEquals("replacement load must remain blocked for the assertion", 1L, fresh.proceed.count)
            assertTrue("new START must cancel the prior ASR consumer before replacement model readiness", oldAsr.isCancelled)
        } finally {
            drain.allow()
            complete(fresh, freshPreparation)
            drainMainUntil("the retired actual ASR consumer must finish") { oldAsr.isCompleted }
        }
        assertSame(fresh, engine(owner.value))
        assertEquals(1, old.releases.get())
        stop(owner)
        owner.destroy()
        assertEquals(1, fresh.releases.get())
    }

    @Test fun retiredInferenceCompletionCannotChangeReplacementCaptionOrTranscript() {
        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = true)
        val owner = service()
        val staleText = "The violet lighthouse belongs to the retired session."
        val inference = ControlledInference(CaptionResult(staleText, "en", 1f, 0L, 1000L))
            .also { inferences += it }
        val drain = ControlledDrain(ShortArray(16_000) { 700 }).also { drains += it; it.allow() }
        CaptureBoundary.nextDrain.set(drain)
        val old = plan("retired-inference-owner").apply { this.inference = inference }
        complete(old, start(owner, old))
        drainMainUntil("actual service consumer must enter controlled blocking inference") {
            inference.entered.count == 0L
        }
        val oldAsr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        val recorder = field(owner.value, "subtitleRecorder") as SubtitleFileRecorder
        assertTrue("the actual recorder must be active for the publication check", recorder.isRecording)
        val transcript = File(recorder.currentPath!!)
        val transcriptBefore = transcript.readBytes()
        val fresh = plan("replacement-pending-after-old-inference")
        val freshPreparation = start(owner, fresh)
        val sequenceBefore = field(owner.value, "captionSeq")
        val hasCaptionBefore = field(owner.value, "hasRealCaption")
        val appendSequenceBefore = field(owner.value, "subtitleAppendSeq")
        val overlay = field(owner.value, "overlayView") as View
        val primaryBefore = overlay.findViewById<TextView>(R.id.captionPrimary).text.toString()
        val secondaryBefore = overlay.findViewById<TextView>(R.id.captionSecondary).text.toString()
        try {
            assertEquals("new model must stay blocked while the old native result returns", 1L, fresh.proceed.count)
            inference.allow()
            drainMainUntil("the old actual consumer must finish after native completion") { oldAsr.isCompleted }
            assertEquals("retired inference must not advance the new session caption sequence", sequenceBefore, field(owner.value, "captionSeq"))
            assertEquals("retired inference must not mark the new session as having speech", hasCaptionBefore, field(owner.value, "hasRealCaption"))
            assertEquals("retired inference must not update subtitle append ownership", appendSequenceBefore, field(owner.value, "subtitleAppendSeq"))
            assertEquals(primaryBefore, overlay.findViewById<TextView>(R.id.captionPrimary).text.toString())
            assertEquals(secondaryBefore, overlay.findViewById<TextView>(R.id.captionSecondary).text.toString())
            assertArrayEquals("retired result must not enter actual transcript bytes", transcriptBefore, transcript.readBytes())
            assertFalse("the stale result must never be rendered", overlay.findViewById<TextView>(R.id.captionPrimary).text.contains(staleText))
        } finally {
            inference.allow()
            complete(fresh, freshPreparation)
            drainMainUntil("the old ASR job must finish before fixture teardown") { oldAsr.isCompleted }
        }
        assertSame(fresh, engine(owner.value))
        assertEquals(1, old.releases.get())
    }

    @Test fun oldAppendAlreadyInFlightCannotWriteIntoReplacementTranscript() {
        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = true)
        val owner = service()
        val oldMarker = "Copper telescope marked the old room."
        val newMarker = "Silver sailboat marked the new room."
        val append = ControlledAppend(oldMarker).also { appends += it }
        RecorderAppendBoundary.nextAppend.set(append)
        val oldInference = ControlledInference(CaptionResult(oldMarker, "en", 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 710 })
            .also { drains += it; it.allow() })
        val old = plan("old-owner-at-append-entry").apply { inference = oldInference }
        complete(old, start(owner, old))
        drainMainUntil("old actual caption must reach real recorder append boundary") { append.entered.count == 0L }
        val oldAsr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        val recorder = field(owner.value, "subtitleRecorder") as SubtitleFileRecorder
        val oldTranscript = File(recorder.currentPath!!)
        val oldBytes = oldTranscript.readBytes()

        val freshInference = ControlledInference(CaptionResult(newMarker, "en", 1f, 1000L, 2000L))
            .also { inferences += it }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 720 })
            .also { drains += it; it.allow() })
        val fresh = plan("new-owner-at-append-entry").apply { inference = freshInference }
        val freshPreparation = start(owner, fresh)
        try {
            complete(fresh, freshPreparation)
            drainMainUntil("fresh actual ASR consumer must enter controlled inference") { freshInference.entered.count == 0L }
            val freshAsr = (field(owner.value, "asrJob") as Job).also { consumers += it }
            val newTranscript = File(recorder.currentPath!!)
            assertNotEquals("replacement must own a new real transcript", oldTranscript.path, newTranscript.path)
            val newHeader = newTranscript.readBytes()
            append.allow()
            drainMainUntil("old append and actual ASR consumer must finish") { oldAsr.isCompleted }
            assertArrayEquals("late old append must not write its marker into the replacement file", newHeader, newTranscript.readBytes())
            assertArrayEquals("replacement must preserve the previous file", oldBytes, oldTranscript.readBytes())
            freshInference.allow()
            drainMainUntil("fresh actual result must publish successfully") { freshAsr.isCompleted }
            val published = newTranscript.readText(Charsets.UTF_8)
            assertTrue("fresh marker must reach its own transcript", published.contains(newMarker))
            assertFalse("retired marker must never enter the fresh transcript", published.contains(oldMarker))
            assertEquals("fresh caption appends exactly once", 1, published.lineSequence().count { it == newMarker })
            val overlay = field(owner.value, "overlayView") as View
            assertEquals(newMarker, overlay.findViewById<TextView>(R.id.captionPrimary).text.toString())
        } finally {
            append.allow()
            freshInference.allow()
            complete(fresh, freshPreparation)
            drainMainUntil("old ASR must finish before cleanup") { oldAsr.isCompleted }
        }
        stop(owner)
        owner.destroy()
        assertEquals(1, old.releases.get())
        assertEquals(1, fresh.releases.get())
    }

    @Test fun captionsWithoutRecordingStillPublishAndLaterRecordingUsesItsOwnSession() {
        val owner = service()
        val unrecordedMarker = "Green lantern stayed in the dry room."
        val recordedMarker = "Purple lantern lights the new room."
        val unrecordedInference = ControlledInference(CaptionResult(unrecordedMarker, "en", 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 730 })
            .also { drains += it; it.allow() })
        val unrecorded = plan("no-recording-owner").apply { inference = unrecordedInference }
        complete(unrecorded, start(owner, unrecorded))
        val unrecordedAsr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("no-recording caption must publish normally") { unrecordedAsr.isCompleted }
        val recorder = field(owner.value, "subtitleRecorder") as SubtitleFileRecorder
        assertNull("disabled recording has no session path", recorder.currentPath)
        assertFalse(recorder.isRecording)
        val overlay = field(owner.value, "overlayView") as View
        assertEquals(unrecordedMarker, overlay.findViewById<TextView>(R.id.captionPrimary).text.toString())

        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = true)
        val recordedInference = ControlledInference(CaptionResult(recordedMarker, "en", 1f, 1000L, 2000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 740 })
            .also { drains += it; it.allow() })
        val recorded = plan("later-recording-owner").apply { inference = recordedInference }
        complete(recorded, start(owner, recorded))
        val recordedAsr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("later enabled recording must publish normally") { recordedAsr.isCompleted }
        assertTrue(recorder.isRecording)
        val transcript = File(recorder.currentPath!!)
        val bytes = transcript.readText(Charsets.UTF_8)
        assertTrue(bytes.contains(recordedMarker))
        assertFalse("the later transcript must not inherit unrecorded prior-session text", bytes.contains(unrecordedMarker))
        assertEquals(recordedMarker, overlay.findViewById<TextView>(R.id.captionPrimary).text.toString())
        stop(owner)
        owner.destroy()
        assertEquals(1, unrecorded.releases.get())
        assertEquals(1, recorded.releases.get())
    }

    @Test fun stopDuringRecorderOpenClosesLateFileAndPreservesItsBytes() {
        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = true)
        val owner = service()
        val opening = ControlledSessionStart().also { recordingStarts += it }
        RecorderAppendBoundary.nextStart.set(opening)
        val pending = plan("stopped-during-recorder-open")
        val preparation = start(owner, pending)
        pending.allow()
        drainMainUntil("actual preparation must enter real recorder start boundary") { opening.entered.count == 0L }
        val recorder = field(owner.value, "subtitleRecorder") as SubtitleFileRecorder
        assertFalse("the controlled start has not yet opened its real file", recorder.isRecording)
        stop(owner)
        try {
            opening.allow()
            drainMainUntil("stopped preparation must finish after the real file opens") { preparation.isCompleted }
            val lateFile = File(requireNotNull(opening.createdPath) { "the actual recorder must have created a real late file" })
            val retained = lateFile.readBytes()
            assertTrue("the late file must contain its actual recorder header", lateFile.readText(Charsets.UTF_8).startsWith("# Caption Action"))
            assertFalse("a stale open after STOP must not leave an active unowned writer", recorder.isRecording)
            assertNull("a stopped preparation must not retain the late path as active", recorder.currentPath)
            owner.destroy()
            assertArrayEquals("late-file cleanup must close, not delete or truncate, recorded bytes", retained, lateFile.readBytes())
            assertEquals(1, pending.releases.get())
        } finally {
            opening.allow()
            complete(pending, preparation)
        }
    }

    private fun publishControlledCaption(owner: ServiceOwner, engineName: String, text: String): ControlledEngine {
        val inference = ControlledInference(CaptionResult(text, "en", 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 750 })
            .also { drains += it; it.allow() })
        val current = plan(engineName).apply { this.inference = inference }
        complete(current, start(owner, current))
        val asr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("$engineName must publish through the actual ASR consumer") { asr.isCompleted }
        return current
    }

    private fun assertLiveDecisionPrivacy(marker: String, target: String, repeat: Boolean, branch: String) {
        org.robolectric.shadows.ShadowLog.clear()
        SettingsBoundary.configured = AppSettings(inputLanguage = target, targetLanguage = target)
        val inference = ControlledInference(CaptionResult(marker, "en", 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        repeat(if (repeat) 2 else 1) {
            CaptureBoundary.extraDrains.add(ControlledDrain(ShortArray(16_000) { 750 })
                .also { drains += it; it.allow() })
        }
        val owner = service()
        val ready = plan("privacy-live-decision").apply { this.inference = inference }
        complete(ready, start(owner, ready))
        val asr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("actual decision must finish") { asr.isCompleted }
        val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertTrue("expected actual decision branch: $logs", logs.contains(branch))
        assertFalse("Source speech leaked: $logs", logs.lowercase().contains(marker.lowercase()))
    }

    @Test fun liveDuplicateDecisionRetainsReasonWithoutLoggingSpeech() =
        assertLiveDecisionPrivacy("Teal lantern guards our private phrase", "en", true, "suppressed recent duplicate")
    @Test fun liveShortSameScriptRetainsReasonWithoutLoggingSpeech() =
        assertLiveDecisionPrivacy("Hola", "en", false, "publish short same-script")
    @Test fun liveShortWrongScriptRetainsReasonWithoutLoggingSpeech() =
        assertLiveDecisionPrivacy("Hola", "zh", false, "held short wrong-script")

    private fun assertLiveTranslationPrivacy(late: Boolean) {
        org.robolectric.shadows.ShadowLog.clear()
        SettingsBoundary.configured = AppSettings(inputLanguage = "es", targetLanguage = "en")
        val source = "La linterna naranja guarda nuestro secreto"
        val output = "Orange lantern guards our private phrase."
        TranslationBoundary.translatedOutput = output
        if (late) TranslationBoundary.translationGate = CountDownLatch(1)
        val inference = ControlledInference(CaptionResult(source, "es", 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 750 })
            .also { drains += it; it.allow() })
        val owner = service()
        val ready = plan("privacy-live-mt").apply { this.inference = inference }
        complete(ready, start(owner, ready))
        val asr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("consumer must finish quick wait") { asr.isCompleted }
        TranslationBoundary.translationGate?.countDown()
        val expectedLog = if (late) "mt applied seq=" else "mt applied-quick"
        drainMainUntil("actual translation publication must finish") {
            org.robolectric.shadows.ShadowLog.getLogs().any { it.msg.contains(expectedLog) } &&
                (field(owner.value, "overlayView") as View).findViewById<TextView>(R.id.captionPrimary).text.toString().replace('\n', ' ') == (if (late) "$source $output" else output)
        }
        val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertFalse("Source speech leaked: $logs", logs.contains(source))
        assertFalse("Translated speech leaked: $logs", logs.contains(output))
    }

    @Test fun liveQuickTranslationIsVisibleWithoutLoggingSpeech() = assertLiveTranslationPrivacy(late = false)
    @Test fun liveLateTranslationIsVisibleWithoutLoggingSpeech() = assertLiveTranslationPrivacy(late = true)

    private fun assertMicLogPrivacy(
        marker: String, source: String = "en", expectedLine: Boolean,
        target: String = source, previousNorm: String? = null, expectedBranch: String? = null,
        failureMarker: String? = null
    ) {
        org.robolectric.shadows.ShadowLog.clear()
        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = false)
        val inference = ControlledInference(CaptionResult(marker, source, 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        val ready = plan("privacy-mic").apply { this.inference = inference }
        val (owner, preparation) = createMic(ready)
        complete(ready, preparation)
        for ((name, value) in listOf("fromCode" to source, "toCode" to target)) {
            owner.value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner.value, value)
        }
        TranslationBoundary.failureMessage = failureMarker
        val heldDrain = ControlledDrain(ShortArray(28_000) { 750 }).also { drains += it }
        CaptureBoundary.nextDrain.set(heldDrain)
        owner.value.javaClass.getDeclaredMethod("startMicCapture").apply { isAccessible = true }.invoke(owner.value)
        previousNorm?.let {
            val run = field(owner.value, "micRun")!!
            for ((name, value) in listOf(
                "lastPublishedNorm" to it, "source" to source, "target" to target,
                "lastEmitAtMs" to android.os.SystemClock.elapsedRealtime()
            )) {
                run.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(run, value)
            }
        }
        heldDrain.allow()
        val asr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("actual Mic consumer must finish") { asr.isCompleted }
        val lines = field(owner.value, "lines") as List<*>
        assertEquals("Mic caption behavior must remain unchanged", expectedLine, lines.any { it.toString().contains(marker) })
        val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertTrue("actual Mic emit branch must log safe metadata", logs.contains("emit"))
        expectedBranch?.let { assertTrue("expected Mic branch: $logs", logs.contains(it)) }
        failureMarker?.let { assertFalse("Mic failure echoed speech: $logs", logs.contains(it)) }
        assertFalse("Mic speech leaked into diagnostics: $logs", logs.contains(marker.take(40)))
    }

    @Test fun micAcceptedSpeechRemainsVisibleWithoutDiagnosticContent() =
        assertMicLogPrivacy("Indigo lantern guards our private phrase.", expectedLine = true)

    @Test fun micJunkRejectionDoesNotLogRecognizedSpeech() =
        assertMicLogPrivacy("foreign language VioletPrivacyMarker", expectedLine = false)

    @Test fun micWrongScriptRejectionDoesNotLogRecognizedSpeech() =
        assertMicLogPrivacy("Scarlet compass protects our secret.", source = "zh", expectedLine = false)

    @Test fun micExplicitFailureRetainsSourceWithoutDiagnosticSpeech() =
        assertMicLogPrivacy("La linterna guarda nuestro secreto", source = "es", target = "en",
            expectedLine = true, expectedBranch = "translateExplicit",
            failureMarker = "MagentaMicFailurePrivacyMarker")

    @Test fun micNearDuplicateDecisionDoesNotLogSpeech() =
        assertMicLogPrivacy("Golden lantern guards our phrase", expectedLine = false,
            previousNorm = "golden lantern guards our phrase now", expectedBranch = "emit skip near-dup")

    // The shared sanitizer normally rejects this first. Control only that upstream
    // boundary to exercise the retained defensive Mic decision, not to imply natural reachability.
    @Implements(value = com.hatsunama.captionaction.inference.AsrJunkFilter::class, isInAndroidSdk = false)
    class SanitizerPassthroughBoundary {
        @Implementation fun sanitizeOrNull(raw: String?): String? = raw
    }
    @Test
    @Config(shadows = [SanitizerPassthroughBoundary::class],
        instrumentedPackages = ["com.hatsunama.captionaction.inference.AsrJunkFilter"])
    fun micDefensiveMetaDecisionDoesNotLogSpeech() =
        assertMicLogPrivacy("foreign language MarigoldPrivacyMarker", expectedLine = false,
            expectedBranch = "emit skip meta/music")

    @Test fun livePolicyFailureRetainsSourceWithoutDiagnosticSpeech() {
        org.robolectric.shadows.ShadowLog.clear()
        val marker = "UltramarineLiveFailurePrivacyMarker"
        val source = "La linterna guarda nuestro secreto"
        TranslationBoundary.failureMessage = marker
        SettingsBoundary.configured = AppSettings(inputLanguage = "es", targetLanguage = "en")
        val inference = ControlledInference(CaptionResult(source, "es", 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 750 })
            .also { drains += it; it.allow() })
        val owner = service()
        val ready = plan("privacy-live-failure").apply { this.inference = inference }
        complete(ready, start(owner, ready))
        val asr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("fallback caption must finish") {
            asr.isCompleted && (field(owner.value, "overlayView") as View)
                .findViewById<TextView>(R.id.captionPrimary).text.toString().replace('\n', ' ') == "$source $source"
        }
        val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertTrue(logs.contains("applyPolicy failed"))
        assertFalse("Live policy failure echoed speech: $logs", logs.contains(marker))
        assertFalse("Live source leaked: $logs", logs.contains(source))
    }

    // CaptionDisplay normally rejects the same script mismatch. Bypass only that
    // earlier display helper so the final publication guard is tested independently.
    @Implements(value = com.hatsunama.captionaction.inference.CaptionDisplay::class, isInAndroidSdk = false)
    class DisplayPassthroughBoundary {
        @Implementation fun primaryAndSecondary(policy: CaptionResult, dual: Boolean, target: String): Pair<String, String?> =
            (policy.translatedText ?: policy.text) to null
    }
    @Test
    @Config(shadows = [DisplayPassthroughBoundary::class],
        instrumentedPackages = ["com.hatsunama.captionaction.inference.CaptionDisplay"])
    fun liveDefensiveFinalScriptGatePreservesUiWithoutDiagnosticSpeech() {
        org.robolectric.shadows.ShadowLog.clear()
        SettingsBoundary.configured = AppSettings(inputLanguage = "es", targetLanguage = "en")
        val source = "La linterna guarda nuestro secreto"
        val marker = "青色灯笼守护我们的秘密"
        TranslationBoundary.translatedOutput = marker
        val inference = ControlledInference(CaptionResult(source, "es", 1f, 0L, 1000L))
            .also { inferences += it }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(16_000) { 750 })
            .also { drains += it; it.allow() })
        val owner = service()
        val ready = plan("privacy-final-script").apply { this.inference = inference }
        complete(ready, start(owner, ready))
        drainMainUntil("inference must reach controlled boundary") { inference.entered.count == 0L }
        val text = (field(owner.value, "overlayView") as View).findViewById<TextView>(R.id.captionPrimary)
        val before = text.text.toString()
        inference.allow()
        val asr = (field(owner.value, "asrJob") as Job).also { consumers += it }
        drainMainUntil("script rejection must finish") { asr.isCompleted }
        assertEquals("rejected translation must not change visible caption", before, text.text.toString())
        val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertTrue(logs.contains("final script gate blocked"))
        assertFalse("Final script gate leaked speech: $logs", logs.contains(marker))
    }

    @Test fun liveCaptionIsVisibleButAbsentFromApplicationLogsWithRecordingDisabled() {
        org.robolectric.shadows.ShadowLog.clear()
        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = false)
        val marker = "Saffron lantern guards the private phrase."
        val owner = service()
        publishControlledCaption(owner, "privacy-service", marker)
        val overlay = field(owner.value, "overlayView") as View
        assertEquals(marker, overlay.findViewById<TextView>(R.id.captionPrimary).text.toString().replace('\n', ' '))
        val recorder = field(owner.value, "subtitleRecorder") as SubtitleFileRecorder
        assertFalse(recorder.isRecording)
        val logs = org.robolectric.shadows.ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertFalse("Visible speech leaked into diagnostics: $logs", logs.contains(marker))
    }

    @Test fun disablingRecordingClosesPriorFilePreservesBytesAndReenableOwnsNewFile() {
        val owner = service()
        val recordedMarker = "Amber lantern marks the first room."
        val disabledMarker = "Blue compass marks the second room."
        val restartedMarker = "White telescope marks the third room."
        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = true)
        val first = publishControlledCaption(owner, "enabled-recording-owner", recordedMarker)
        val recorder = field(owner.value, "subtitleRecorder") as SubtitleFileRecorder
        assertTrue(recorder.isRecording)
        val previousFile = File(recorder.currentPath!!)
        val previousBytes = previousFile.readBytes()
        assertTrue(previousFile.readText(Charsets.UTF_8).contains(recordedMarker))

        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = false)
        val disabled = publishControlledCaption(owner, "disabled-recording-owner", disabledMarker)
        assertFalse("a disabled replacement must close the previous active recording", recorder.isRecording)
        assertNull("a disabled replacement must own no recorder path", recorder.currentPath)
        assertArrayEquals("disabled-session speech must not append to the earlier transcript", previousBytes, previousFile.readBytes())
        val overlay = field(owner.value, "overlayView") as View
        assertEquals("disabled recording must still allow current captions", disabledMarker,
            overlay.findViewById<TextView>(R.id.captionPrimary).text.toString())

        SettingsBoundary.configured = AppSettings(saveSubtitlesToFile = true)
        val restarted = publishControlledCaption(owner, "reenabled-recording-owner", restartedMarker)
        assertTrue(recorder.isRecording)
        val currentFile = File(recorder.currentPath!!)
        assertNotEquals(previousFile.path, currentFile.path)
        val currentText = currentFile.readText(Charsets.UTF_8)
        assertTrue(currentText.contains(restartedMarker))
        assertFalse(currentText.contains(recordedMarker))
        assertFalse(currentText.contains(disabledMarker))
        assertArrayEquals(previousBytes, previousFile.readBytes())
        stop(owner)
        owner.destroy()
        listOf(first, disabled, restarted).forEach { assertEquals(1, it.releases.get()) }
    }

    @Test fun replacementStartReleasesPreviouslyPublishedEngineExactlyOnce() {
        val owner = service()
        val old = plan("published-old")
        complete(old, start(owner, old))
        assertSame(old, engine(owner.value))
        val fresh = plan("replacement")
        complete(fresh, start(owner, fresh))
        assertSame(fresh, engine(owner.value))
        assertEquals("replacing the published engine must not leak its model", 1, old.releases.get())
        assertEquals(0, fresh.releases.get())
        stop(owner)
        stop(owner)
        owner.destroy()
        assertEquals(1, old.releases.get())
        assertEquals(1, fresh.releases.get())
    }

    @Test fun stopDuringBlockedServiceLoadReclaimsLocalEngineAndFreshServiceCanStart() {
        val owner = service()
        val old = plan("stopped-load")
        val job = start(owner, old)
        stop(owner)
        complete(old, job)
        assertNull("STOP must not publish a late loaded engine", engine(owner.value))
        assertEquals(1, old.releases.get())
        assertEquals(0, TranslationBoundary.constructions.get())
        owner.destroy()
        assertEquals(1, old.releases.get())

        val nextOwner = service()
        val next = plan("legitimate-fresh-service")
        complete(next, start(nextOwner, next))
        assertSame(next, engine(nextOwner.value))
        assertEquals(0, next.releases.get())
        stop(nextOwner)
        nextOwner.destroy()
        assertEquals(1, next.releases.get())
    }

    @Test fun destroyDuringBlockedServiceLoadReclaimsUnpublishedEngine() {
        val owner = service()
        val pending = plan("destroyed-service-load")
        val job = start(owner, pending)
        owner.destroy()
        complete(pending, job)
        assertNull(engine(owner.value))
        assertEquals(1, pending.releases.get())
        assertEquals(0, TranslationBoundary.constructions.get())
    }

    @Test fun stopAfterServiceLoadReturnsButBeforeMainPublicationReclaimsEngine() {
        val owner = service()
        val pending = plan("service-main-return")
        val job = start(owner, pending)
        queueMainReturn(pending, job)
        assertNull(engine(owner.value))
        stop(owner)
        drainMainUntil("stopped main-return preparation must finish") { job.isCompleted }
        assertNull(engine(owner.value))
        assertEquals(1, pending.releases.get())
        assertEquals(0, TranslationBoundary.constructions.get())
    }

    @Test fun micDestroyDuringBlockedLoadReclaimsLocalEngineAndRecreatedOwnerCanStart() {
        val old = plan("destroyed-mic-load")
        val (oldOwner, oldJob) = createMic(old)
        oldOwner.destroy()
        val fresh = plan("recreated-mic")
        val (freshOwner, freshJob) = createMic(fresh)
        complete(fresh, freshJob)
        assertSame(fresh, engine(freshOwner.value))
        complete(old, oldJob)
        assertNull(engine(oldOwner.value))
        assertSame("retired activity cannot affect a recreated owner", fresh, engine(freshOwner.value))
        assertEquals(1, old.releases.get())
        assertEquals(0, fresh.releases.get())
        assertEquals(1, TranslationBoundary.constructions.get())
        freshOwner.destroy()
        assertEquals(1, fresh.releases.get())
    }

    @Test fun micDestroyAfterLoadReturnsButBeforeMainPublicationReclaimsEngine() {
        val pending = plan("mic-main-return")
        val (owner, job) = createMic(pending)
        queueMainReturn(pending, job)
        assertNull(engine(owner.value))
        owner.destroy()
        drainMainUntil("destroyed mic main-return preparation must finish") { job.isCompleted }
        assertNull(engine(owner.value))
        assertEquals(1, pending.releases.get())
        assertEquals(0, TranslationBoundary.constructions.get())
    }

    @Test fun successfulMicPreparationPublishesAndDestroyReleasesOnce() {
        val ready = plan("ready-mic")
        val (owner, job) = createMic(ready)
        complete(ready, job)
        assertSame(ready, engine(owner.value))
        assertEquals(true, field(owner.value, "engineReady"))
        assertEquals(0, ready.releases.get())
        owner.destroy()
        assertNull(engine(owner.value))
        assertEquals(1, ready.releases.get())
    }

    @Test fun failedServiceLoadReleasesUnpublishedEngineWithoutTranslation() {
        val owner = service()
        val failed = plan("failed-service-load").apply { succeeds = false }
        complete(failed, start(owner, failed))
        assertNull(engine(owner.value))
        assertEquals(1, failed.releases.get())
        assertEquals(0, TranslationBoundary.constructions.get())
    }

    @Test fun failedMicLoadReleasesUnpublishedEngineWithoutTranslation() {
        val failed = plan("failed-mic-load").apply { succeeds = false }
        val (owner, job) = createMic(failed)
        complete(failed, job)
        assertNull(engine(owner.value))
        assertEquals(1, failed.releases.get())
        assertEquals(0, TranslationBoundary.constructions.get())
    }
}
