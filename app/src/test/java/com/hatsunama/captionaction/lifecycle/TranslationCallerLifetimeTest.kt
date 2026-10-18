package com.hatsunama.captionaction.lifecycle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.data.SubtitleFileRecorder
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.MlKitTaskBoundary
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.CaptureBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledDrain
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledEngine
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledInference
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.FactoryBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.SettingsBoundary
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * Lifecycle regression tests with the actual service/activity and MlKitTranslationEngine.
 * Only settings, model availability, ASR/capture/projection, and SDK factories/tasks are fake.
 * In particular, the whole-engine TranslationBoundary is deliberately absent.
 *
 * TaskCompletionSource establishes SDK completion independently of coroutine cancellation.
 * No sleep, timeout expiry, synthetic owner, or reflected private owner method establishes
 * a lifecycle transition: START/STOP, create/destroy, and the real mic button do that work.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    sdk = [35],
    application = CaptionActionApp::class,
    shadows = [
        FactoryBoundary::class,
        SettingsBoundary::class,
        CallerPreparationOwnershipTest.ModelAvailabilityBoundary::class,
        CaptureBoundary::class,
        CallerPreparationOwnershipTest.ProjectionManagerBoundary::class,
        CallerPreparationOwnershipTest.ProjectionBoundary::class,
        CallerPreparationOwnershipTest.ImageReaderBoundary::class,
        CallerPreparationOwnershipTest.RecorderAppendBoundary::class,
        MlKitTaskBoundary.TranslationFactory::class,
        MlKitTaskBoundary.IdentifierFactory::class,
        MlKitTaskBoundary.ModelManager::class
    ],
    instrumentedPackages = [
        "com.hatsunama.captionaction.inference.InferenceEngineFactory",
        "com.hatsunama.captionaction.data.SettingsRepository",
        "com.hatsunama.captionaction.data.ModelCache",
        "com.hatsunama.captionaction.audio.AudioCapture",
        "com.hatsunama.captionaction.data.SubtitleFileRecorder",
        "com.google.mlkit.nl.translate.Translation",
        "com.google.mlkit.nl.languageid.LanguageIdentification",
        "com.google.mlkit.common.model.RemoteModelManager"
    ]
)
class TranslationCallerLifetimeTest {
    private class ServiceOwner(val controller: ServiceController<CaptionOverlayService>) {
        val value: CaptionOverlayService get() = controller.get()
        private var destroyed = false
        fun destroy() { if (!destroyed) { destroyed = true; controller.destroy() } }
    }

    private class MicOwner(val controller: ActivityController<MicTranslatorActivity>) {
        val value: MicTranslatorActivity get() = controller.get()
        private var destroyed = false
        fun destroy() { if (!destroyed) { destroyed = true; controller.destroy() } }
    }

    private data class PendingServiceTranslation(
        val engine: ControlledEngine,
        val translation: MlKitTranslationEngine,
        val client: MlKitTaskBoundary.Client,
        val deferred: Deferred<*>,
        val asr: Job
    )

    private data class PendingMicTranslation(
        val owner: MicOwner,
        val engine: ControlledEngine,
        val client: MlKitTaskBoundary.Client,
        val asr: Job
    )

    private data class OverlayState(
        val primary: String,
        val secondary: String,
        val status: String,
        val statusVisibility: Int,
        val loadingVisibility: Int,
        val captionSequence: Any?,
        val subtitleSequence: Any?,
        val hasCaption: Any?,
        val publishedAt: Any?
    )

    private val appends = mutableListOf<CallerPreparationOwnershipTest.ControlledAppend>()
    private val engines = mutableListOf<ControlledEngine>()
    private val drains = mutableListOf<ControlledDrain>()
    private val inferences = mutableListOf<ControlledInference>()
    private val services = mutableListOf<ServiceOwner>()
    private val microphones = mutableListOf<MicOwner>()
    private val observedJobs = mutableListOf<Job>()
    private val modelQueries = mutableListOf<TaskCompletionSource<Set<TranslateRemoteModel>>>()
    private val modelDownloads = mutableListOf<TaskCompletionSource<Void>>()
    private var nextStartId = 1

    @Before fun resetBoundaries() {
        CallerPreparationOwnershipTest.RecorderAppendBoundary.nextAppend.set(null)
        CallerPreparationOwnershipTest.RecorderAppendBoundary.nextStart.set(null)
        FactoryBoundary.planned.clear()
        CaptureBoundary.nextDrain.set(null)
        CaptureBoundary.extraDrains.clear()
        SettingsBoundary.configured = AppSettings(
            inputLanguage = "zh", targetLanguage = "en", saveSubtitlesToFile = true
        )
        RuntimeEnvironment.getApplication().getSharedPreferences("mic_translator", Context.MODE_PRIVATE)
            .edit().clear().putString("mic_from", "es").putString("mic_to", "en").commit()
        MlKitTaskBoundary.reset()
    }

    @After fun releaseFixture() {
        appends.forEach { it.allow() }
        engines.forEach { it.allow() }
        drains.forEach { it.allow() }
        inferences.forEach { it.allow() }
        services.forEach { owner ->
            observedJobs += serviceScope(owner).coroutineContext[Job]!!.children.toList()
            owner.destroy()
        }
        microphones.forEach { owner ->
            observedJobs += owner.value.lifecycleScope.coroutineContext[Job]!!.children.toList()
            owner.destroy()
        }
        modelQueries.forEach { it.trySetException(IllegalStateException("Fixture model-query cleanup")) }
        modelDownloads.forEach { it.trySetException(IllegalStateException("Fixture model-download cleanup")) }
        MlKitTaskBoundary.unblock()
        drainMainUntil("actual lifecycle jobs must finish in cleanup") {
            MlKitTaskBoundary.unblock()
            observedJobs.all { it.isCompleted }
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun serviceScope(owner: ServiceOwner): CoroutineScope =
        field(owner.value, "scope") as CoroutineScope

    private fun drainMainUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(message, condition())
    }

    private fun service(): ServiceOwner =
        ServiceOwner(Robolectric.buildService(CaptionOverlayService::class.java).create())
            .also { services += it }

    private fun plan(name: String, source: String, language: String = "zh"): ControlledEngine {
        val inference = ControlledInference(CaptionResult(source, language, 1f, 0L, 1000L))
            .also { inferences += it; it.allow() }
        CaptureBoundary.nextDrain.set(ControlledDrain(ShortArray(28_000) { 730 })
            .also { drains += it; it.allow() })
        return ControlledEngine(name).also {
            it.inference = inference
            engines += it
            FactoryBoundary.planned.add(it)
        }
    }

    private fun start(owner: ServiceOwner, expected: ControlledEngine): Job {
        val intent = Intent().apply {
            action = CaptionOverlayService.ACTION_START
            putExtra(CaptionOverlayService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptionOverlayService.EXTRA_RESULT_DATA, Intent("fixture-projection-grant"))
        }
        assertEquals(android.app.Service.START_NOT_STICKY,
            owner.value.onStartCommand(intent, 0, nextStartId++))
        drainMainUntil("${expected.name} must enter actual START preparation") {
            expected.entered.count == 0L
        }
        return (field(owner.value, "preparationJob") as Job).also { observedJobs += it }
    }

    private fun completePreparation(engine: ControlledEngine, preparation: Job) {
        engine.allow()
        drainMainUntil("${engine.name} must finish actual preparation") { preparation.isCompleted }
    }

    private fun stop(owner: ServiceOwner) {
        owner.value.onStartCommand(Intent().setAction(CaptionOverlayService.ACTION_STOP), 0, nextStartId++)
    }

    private fun client(index: Int): MlKitTaskBoundary.Client {
        drainMainUntil("actual translation engine must invoke SDK client $index") {
            MlKitTaskBoundary.translators.getOrNull(index)?.invoked?.count == 0L
        }
        return MlKitTaskBoundary.translators[index].also {
            assertEquals("one actual SDK task per controlled ASR window", 1, it.tasks.size)
            assertFalse("SDK work must remain pending at the lifecycle boundary", it.tasks.single().task.isComplete)
            assertEquals(0, it.closes.get())
        }
    }

    private fun pendingService(
        owner: ServiceOwner, engine: ControlledEngine, clientIndex: Int
    ): PendingServiceTranslation {
        val sdk = client(clientIndex)
        val deferred = serviceScope(owner).coroutineContext[Job]!!.children
            .filterIsInstance<Deferred<*>>().single { !it.isCompleted && !it.isCancelled }
        assertFalse(deferred.isCancelled)
        val asr = field(owner.value, "asrJob") as Job
        observedJobs += deferred
        observedJobs += asr
        return PendingServiceTranslation(
            engine, field(owner.value, "translation") as MlKitTranslationEngine, sdk, deferred, asr
        )
    }

    private fun overlay(owner: ServiceOwner): View = field(owner.value, "overlayView") as View
    private fun recorder(owner: ServiceOwner): SubtitleFileRecorder =
        field(owner.value, "subtitleRecorder") as SubtitleFileRecorder

    private fun snapshot(owner: ServiceOwner, view: View): OverlayState = OverlayState(
        view.findViewById<TextView>(R.id.captionPrimary).text.toString(),
        view.findViewById<TextView>(R.id.captionSecondary).text.toString(),
        view.findViewById<TextView>(R.id.captionStatus).text.toString(),
        view.findViewById<View>(R.id.captionStatus).visibility,
        view.findViewById<View>(R.id.captionLoading).visibility,
        field(owner.value, "captionSeq"), field(owner.value, "subtitleAppendSeq"),
        field(owner.value, "hasRealCaption"), field(owner.value, "lastPublishedAtMs")
    )

    private fun assertPendingSdkRetained(client: MlKitTaskBoundary.Client) {
        assertFalse("cancelling an awaiter must not claim SDK completion", client.tasks.single().task.isComplete)
        assertEquals("an actual pending SDK task must retain its translator", 0, client.closes.get())
    }

    private fun pendingPresentationWait(owner: ServiceOwner): Job? =
        (field(owner.value, "mtJobs") as List<*>).filterIsInstance<Job>().firstOrNull { job ->
            job !is Deferred<*> && job.children.any { it.javaClass.simpleName == "TimeoutCoroutine" }
        }

    private fun heldNextDrain(): ControlledDrain = ControlledDrain().also {
        drains += it
        CaptureBoundary.extraDrains.add(it)
    }

    @Test fun pendingChineseTranslationDoesNotBlockNextLivePcmDrain() {
        assertPendingTranslationDoesNotBlockDrain("蓝色灯笼守护着海港的秘密。", "zh")
    }

    @Test fun pendingSameScriptTranslationDoesNotBlockNextLivePcmDrain() {
        assertPendingTranslationDoesNotBlockDrain("La linterna azul protege nuestro puerto.", "es")
    }

    private fun assertPendingTranslationDoesNotBlockDrain(source: String, language: String) {
        val owner = service()
        val engine = plan("live-mt-cadence", source, language)
        // The second drain is a barrier, not a simulated consumer or elapsed-time assertion.
        val next = ControlledDrain().also { drains += it; CaptureBoundary.extraDrains.add(it) }
        completePreparation(engine, start(owner, engine))
        val pending = pendingService(owner, engine, 0)
        val view = overlay(owner)
        val before = snapshot(owner, view)
        val file = File(recorder(owner).currentPath!!)
        val beforeBytes = file.readBytes()

        // The baseline suspends the ASR Job in its own withTimeoutOrNull child. The
        // repaired consumer enters the next drain while a sibling presentation Job waits.
        // Whichever boundary occurs establishes ordering; no timeout expiry is evidence.
        drainMainUntil("consumer must reach its next drain or its owned MT timeout boundary") {
            (next.entered.count == 0L && pendingPresentationWait(owner) != null) || pending.asr.children.any {
                it.javaClass.simpleName == "TimeoutCoroutine"
            }
        }
        assertEquals("pending MT presentation must not hold the Live PCM consumer", 0L, next.entered.count)
        assertNotNull("presentation wait must still be pending when capture advances", pendingPresentationWait(owner))
        assertFalse("no SDK completion was used to advance capture", pending.client.tasks.single().task.isComplete)
        assertEquals("pending source must not replace the overlay", before, snapshot(owner, view))
        assertArrayEquals("pending source must not append to transcript", beforeBytes, file.readBytes())

        val translated = "The blue lantern protects our harbor."
        pending.client.complete(translated)
        drainMainUntil("translation must publish even while next PCM drain remains blocked") {
            view.findViewById<TextView>(R.id.captionPrimary).text.toString().replace('\n', ' ') == translated
        }
        assertEquals(1L, next.proceed.count)
        val recorded = file.readLines().filterNot { it.startsWith("#") || it.isBlank() }.joinToString(" ")
        assertEquals("translated output must be recorded once", translated, recorded)
        stop(owner)
        assertTrue("STOP cancels the pending consumer", pending.asr.isCancelled)
    }

    @Test fun newerLiveCaptionSupersedesPendingPresentationWithinSameSession() {
        val owner = service()
        val engine = plan("live-mt-sequence", "蓝色灯笼守护着海港的秘密。", "zh")
        val next = ControlledDrain(ShortArray(28_000) { 750 })
            .also { drains += it; CaptureBoundary.extraDrains.add(it) }
        val tail = ControlledDrain().also { drains += it; CaptureBoundary.extraDrains.add(it) }
        completePreparation(engine, start(owner, engine))
        val first = pendingService(owner, engine, 0)
        drainMainUntil("consumer must reach next drain or its owned timeout") {
            (next.entered.count == 0L && pendingPresentationWait(owner) != null) || first.asr.children.any { it.javaClass.simpleName == "TimeoutCoroutine" }
        }
        assertEquals("new audio must advance independently of old MT", 0L, next.entered.count)
        assertNotNull("old timeout expiry must not establish successful scheduling", pendingPresentationWait(owner))
        val firstJobs = (field(owner.value, "mtJobs") as List<*>).filterIsInstance<Job>().toList()
        engine.inference = ControlledInference(
            CaptionResult("La vela plateada llega al nuevo puerto.", "es", 1f, 1000L, 2000L)
        ).also { inferences += it; it.allow() }
        next.allow()
        val secondClient = client(1)
        drainMainUntil("second result must reach next capture boundary") { tail.entered.count == 0L }
        val view = overlay(owner)
        val before = snapshot(owner, view)
        val file = File(recorder(owner).currentPath!!)
        val bytes = file.readBytes()
        first.client.complete("The retired lighthouse must never appear.")
        drainMainUntil("old actual MT and presentation jobs must finish") { firstJobs.all { it.isCompleted } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("old sequence must not replace current UI", before, snapshot(owner, view))
        assertArrayEquals("old sequence must not record a superseded translation", bytes, file.readBytes())

        val fresh = "The silver sail reaches the new harbor."
        secondClient.complete(fresh)
        drainMainUntil("latest sequence must publish") {
            view.findViewById<TextView>(R.id.captionPrimary).text.toString().replace('\n', ' ') == fresh
        }
        assertEquals(fresh, file.readLines().filterNot { it.startsWith("#") || it.isBlank() }.joinToString(" "))
        stop(owner)
    }

    @Test fun concurrentQuickTranslationsRecordInPublicationOrder() {
        val owner = service()
        val secondLockAttempt = CountDownLatch(1)
        val attempts = AtomicInteger()
        // Observe only lock entry; delegate the actual Mutex unchanged. The red
        // pre-serialization candidate has no field and proves real reversed IO.
        owner.value.javaClass.declaredFields.firstOrNull { it.name == "publicationTransition" }?.let { field ->
            field.isAccessible = true
            val actual = field.get(owner.value) as Mutex
            field.set(owner.value, object : Mutex by actual {
                override suspend fun lock(owner: Any?) {
                    if (attempts.incrementAndGet() == 2) secondLockAttempt.countDown()
                    actual.lock(owner)
                }
            })
        }
        val firstText = "The first blue lantern guards the harbor."
        val secondText = "The second silver sail reaches the harbor."
        val append = CallerPreparationOwnershipTest.ControlledAppend("lantern").also {
            appends += it
            CallerPreparationOwnershipTest.RecorderAppendBoundary.nextAppend.set(it)
        }
        val engine = plan("publication-order", "蓝色灯笼守护着海港的秘密。", "zh")
        val next = ControlledDrain(ShortArray(28_000) { 750 })
            .also { drains += it; CaptureBoundary.extraDrains.add(it) }
        completePreparation(engine, start(owner, engine))
        val first = pendingService(owner, engine, 0)
        drainMainUntil("next PCM drain must be independent of pending MT") { next.entered.count == 0L }
        first.client.complete(firstText)
        drainMainUntil("first publication must claim sequence then pause before recorder IO") { append.entered.count == 0L }
        val file = File(recorder(owner).currentPath!!)
        assertFalse(file.readText().contains(firstText))
        engine.inference = ControlledInference(
            CaptionResult("La vela plateada llega al nuevo puerto.", "es", 1f, 1000L, 2000L)
        ).also { inferences += it; it.allow() }
        next.allow()
        val second = client(1)
        second.complete(secondText)
        drainMainUntil("second publication must attempt serialization or expose reversed append") {
            secondLockAttempt.count == 0L || file.readText().replace('\n', ' ').contains(secondText)
        }
        append.allow()
        drainMainUntil("both actual publication jobs must finish") {
            (field(owner.value, "mtJobs") as List<*>).filterIsInstance<Job>().all { it.isCompleted }
        }
        val recorded = file.readLines().filterNot { it.startsWith("#") || it.isBlank() }.joinToString(" ")
        val visible = overlay(owner).findViewById<TextView>(R.id.captionPrimary).text.toString().replace('\n', ' ')
        assertTrue("latest caption must remain last in the existing rolling display", visible.endsWith(secondText))
        assertEquals("concurrent quick MT must record first caption before the newer rolling display", "$firstText $visible", recorded)
        stop(owner)
    }

    @Test fun replacementStartCancelsActualTranslationDeferredAndRetiresOnlyItsOwnClient() {
        val owner = service()
        val oldEngine = plan("old-service-mt", "蓝色灯笼守护着旧房间的秘密。")
        val oldDrain = heldNextDrain()
        completePreparation(oldEngine, start(owner, oldEngine))
        val old = pendingService(owner, oldEngine, 0)
        drainMainUntil("old consumer and presentation must both be pending") { oldDrain.entered.count == 0L && pendingPresentationWait(owner) != null }
        val oldPresentation = pendingPresentationWait(owner)!!
        val oldFile = File(recorder(owner).currentPath!!)
        val oldBytes = oldFile.readBytes()

        val freshEngine = plan("replacement-service-mt", "银色帆船驶向崭新的海港。")
        val preparation = start(owner, freshEngine)
        assertEquals("replacement ASR model load stays blocked for this assertion", 1L, freshEngine.proceed.count)
        assertTrue("new START must cancel the actual SDK-awaiting Deferred, not only its result applier", old.deferred.isCancelled)
        assertTrue("new START must cancel the retired ASR consumer", old.asr.isCancelled)
        assertTrue("new START must cancel the tracked presentation", oldPresentation.isCancelled)
        oldDrain.allow()
        assertPendingSdkRetained(old.client)

        completePreparation(freshEngine, preparation)
        val fresh = pendingService(owner, freshEngine, 1)
        assertNotSame(old.translation, fresh.translation)
        assertNotSame(old.client, fresh.client)
        assertSame(freshEngine, field(owner.value, "engine"))
        assertEquals(1, oldEngine.releases.get())
        val newFile = File(recorder(owner).currentPath!!)
        assertNotEquals(oldFile.path, newFile.path)
        val newBytes = newFile.readBytes()
        val view = overlay(owner)
        drainMainUntil("new owner model readiness must settle before status snapshot") {
            fresh.translation.statusLine().startsWith("MT packs ready")
        }
        val stable = snapshot(owner, view)
        val status = fresh.translation.statusLine()

        old.client.complete("The retired violet lighthouse must never appear.")
        drainMainUntil("retired SDK completion must close exactly its client and finish its jobs") {
            old.client.closes.get() == 1 && old.deferred.isCompleted && old.asr.isCompleted
        }
        assertTrue(old.deferred.isCancelled)
        assertEquals("old completion cannot publish caption, status, spinner or output ownership", stable, snapshot(owner, view))
        assertEquals("old completion cannot change the new MT owner's status", status, fresh.translation.statusLine())
        assertArrayEquals("old completion cannot append to the replacement transcript", newBytes, newFile.readBytes())
        assertArrayEquals("old transcript must remain unchanged", oldBytes, oldFile.readBytes())
        assertFalse("fresh actual Deferred must survive old SDK completion", fresh.deferred.isCancelled)
        assertPendingSdkRetained(fresh.client)

        val translated = "The silver sailboat reaches the new harbor."
        fresh.client.complete(translated)
        drainMainUntil("fresh translation and every actual result-applier job must finish") {
            fresh.deferred.isCompleted && fresh.asr.isCompleted &&
                (field(owner.value, "mtJobs") as List<*>).filterIsInstance<Job>().all { it.isCompleted }
        }
        val visible = view.findViewById<TextView>(R.id.captionPrimary).text.toString()
        assertEquals("fresh owner must publish the complete translated sentence", translated, visible.replace('\n', ' '))
        val recorded = newFile.readLines().filterNot { it.startsWith("#") || it.isBlank() }.joinToString("\n")
        assertEquals("real transcript must contain exactly the same wrapped caption as the overlay", visible, recorded)
        assertEquals("translated sentence must appear exactly once in the real transcript", translated, recorded.replace('\n', ' '))
        assertFalse(newFile.readText().contains("retired violet"))
        assertEquals(0, fresh.client.closes.get())
        stop(owner)
        stop(owner)
        owner.destroy()
        assertEquals(1, old.client.closes.get())
        assertEquals(1, fresh.client.closes.get())
        assertEquals(1, oldEngine.releases.get())
        assertEquals(1, freshEngine.releases.get())
    }

    @Test fun stopCancelsActualTranslationDeferredButKeepsClientUntilSdkCompletion() {
        assertServiceTeardownRetainsPendingTask(destroyDirectly = false)
    }

    @Test fun destroyCancelsActualTranslationDeferredButKeepsClientUntilSdkCompletion() {
        assertServiceTeardownRetainsPendingTask(destroyDirectly = true)
    }

    private fun assertServiceTeardownRetainsPendingTask(destroyDirectly: Boolean) {
        val owner = service()
        val engine = plan("retiring-service-mt", "蓝色灯笼守护着旧房间的秘密。")
        val held = heldNextDrain()
        completePreparation(engine, start(owner, engine))
        val pending = pendingService(owner, engine, 0)
        drainMainUntil("consumer and presentation must both be pending before teardown") { held.entered.count == 0L && pendingPresentationWait(owner) != null }
        val presentation = pendingPresentationWait(owner)!!
        val view = overlay(owner)
        val file = File(recorder(owner).currentPath!!)
        val before = file.readBytes()
        if (destroyDirectly) owner.destroy() else stop(owner)
        assertTrue("teardown must cancel the actual SDK-awaiting Deferred", pending.deferred.isCancelled)
        assertTrue(pending.asr.isCancelled)
        assertTrue("teardown must cancel the tracked presentation", presentation.isCancelled)
        held.allow()
        assertPendingSdkRetained(pending.client)
        assertNull(field(owner.value, "translation"))
        assertNull(field(owner.value, "overlayView"))
        assertFalse(recorder(owner).isRecording)
        val stable = snapshot(owner, view)

        pending.client.complete("The retired crimson telescope must never appear.")
        drainMainUntil("SDK completion after teardown must close exactly once") {
            pending.client.closes.get() == 1 && pending.deferred.isCompleted && pending.asr.isCompleted
        }
        assertEquals("retired completion cannot change detached caption or status views", stable, snapshot(owner, view))
        assertArrayEquals("retired completion cannot mutate retained transcript bytes", before, file.readBytes())
        assertNull(field(owner.value, "overlayView"))
        if (!destroyDirectly) stop(owner)
        owner.destroy()
        assertEquals(1, pending.client.closes.get())
        assertEquals(1, engine.releases.get())
    }

    @Test fun replacementCancelsActualReadinessChildAndLateQueryCannotPublishStatusOrDownload() {
        assertRetiredReadinessCannotPublish(downloadPending = false)
    }

    @Test fun replacementCancelsActualReadinessChildAndLateDownloadCannotPublishStatus() {
        assertRetiredReadinessCannotPublish(downloadPending = true)
    }

    private fun assertRetiredReadinessCannotPublish(downloadPending: Boolean) {
        val query = TaskCompletionSource<Set<TranslateRemoteModel>>().also { modelQueries += it }
        val download = if (downloadPending) TaskCompletionSource<Void>().also { modelDownloads += it } else null
        if (downloadPending) query.setResult(emptySet())
        MlKitTaskBoundary.downloadedTask = query.task
        MlKitTaskBoundary.downloadTask = download?.task
        val owner = service()
        val oldEngine = ControlledEngine("old-service-readiness").also {
            engines += it
            FactoryBoundary.planned.add(it)
        }
        val oldPreparation = start(owner, oldEngine)
        completePreparation(oldEngine, oldPreparation)
        drainMainUntil("real old readiness must enter the pending SDK model operation") {
            if (downloadPending) MlKitTaskBoundary.downloadEntered.latch.count == 0L
            else MlKitTaskBoundary.downloadedEntered.latch.count == 0L
        }
        val downloadsBefore = MlKitTaskBoundary.downloadCalls.get()
        assertEquals(if (downloadPending) 1 else 0, downloadsBefore)
        val oldAsr = (field(owner.value, "asrJob") as Job).also { observedJobs += it }
        drainMainUntil("empty capture must finish the old ASR child before identifying readiness") { oldAsr.isCompleted }
        val oldReadiness = serviceScope(owner).coroutineContext[Job]!!.children.single {
            it !== oldPreparation && it !== oldAsr && it !is Deferred<*>
        }.also { observedJobs += it }
        assertFalse(oldReadiness.isCompleted)
        val oldTranslation = field(owner.value, "translation") as MlKitTranslationEngine
        val oldStatus = oldTranslation.statusLine()
        val oldFile = File(recorder(owner).currentPath!!)
        val oldBytes = oldFile.readBytes()

        // Only the old SDK operation remains blocked. The replacement sees ready models.
        MlKitTaskBoundary.downloadedTask = null
        MlKitTaskBoundary.downloadTask = null
        val freshEngine = ControlledEngine("replacement-service-readiness").also {
            engines += it
            FactoryBoundary.planned.add(it)
        }
        val preparation = start(owner, freshEngine)
        assertTrue("new START must cancel the actual readiness child, not just caption work", oldReadiness.isCancelled)
        assertFalse("cancelling readiness must not pretend its SDK operation completed",
            download?.task?.isComplete ?: query.task.isComplete)
        completePreparation(freshEngine, preparation)
        val freshTranslation = field(owner.value, "translation") as MlKitTranslationEngine
        val freshAsr = (field(owner.value, "asrJob") as Job).also { observedJobs += it }
        drainMainUntil("replacement model readiness and empty ASR must settle") {
            freshTranslation.statusLine().startsWith("MT packs ready") && freshAsr.isCompleted
        }
        val view = overlay(owner)
        val stable = snapshot(owner, view)
        val freshStatus = freshTranslation.statusLine()
        val toastCount = ShadowToast.shownToastCount()
        val freshFile = File(recorder(owner).currentPath!!)
        val freshBytes = freshFile.readBytes()
        assertNotEquals(oldFile.path, freshFile.path)

        if (download != null) download.setException(IllegalStateException("Late retired model-download failure"))
        else query.setException(IllegalStateException("Late retired model-query failure"))
        drainMainUntil("actual retired readiness child must finish after SDK failure") { oldReadiness.isCompleted }
        assertEquals("retired SDK failure must not launch another model download", downloadsBefore, MlKitTaskBoundary.downloadCalls.get())
        assertEquals("retired readiness must not publish a failure Toast", toastCount, ShadowToast.shownToastCount())
        assertEquals("retired readiness must not update overlay caption, status or spinner", stable, snapshot(owner, view))
        assertEquals("cancelled readiness must leave its own last status unchanged", oldStatus, oldTranslation.statusLine())
        assertEquals("retired readiness must not change the new owner's status", freshStatus, freshTranslation.statusLine())
        assertSame(freshTranslation, field(owner.value, "translation"))
        assertSame(freshEngine, field(owner.value, "engine"))
        assertArrayEquals(oldBytes, oldFile.readBytes())
        assertArrayEquals(freshBytes, freshFile.readBytes())
        assertTrue("model preparation must not create unrelated translation clients", MlKitTaskBoundary.translators.isEmpty())
        assertEquals(1, oldEngine.releases.get())
        assertEquals(0, freshEngine.releases.get())
        stop(owner)
        owner.destroy()
        assertEquals(1, oldEngine.releases.get())
        assertEquals(1, freshEngine.releases.get())
    }

    private fun startMic(name: String, source: String, clientIndex: Int): PendingMicTranslation {
        val engine = plan(name, source, "es")
        val owner = MicOwner(Robolectric.buildActivity(MicTranslatorActivity::class.java).create())
            .also { microphones += it }
        drainMainUntil("$name must enter actual activity preparation") { engine.entered.count == 0L }
        val preparation = owner.value.lifecycleScope.coroutineContext[Job]!!.children.single()
            .also { observedJobs += it }
        completePreparation(engine, preparation)
        assertEquals(true, field(owner.value, "engineReady"))
        assertTrue(owner.value.findViewById<MaterialButton>(R.id.btnMic).performClick())
        val sdk = client(clientIndex)
        val asr = (field(owner.value, "asrJob") as Job).also { observedJobs += it }
        assertFalse(asr.isCancelled)
        assertEquals(source, owner.value.findViewById<TextView>(R.id.captionText).text.toString())
        return PendingMicTranslation(owner, engine, sdk, asr)
    }

    @Test fun micDestroyCancelsActualAwaiterAndLateTaskCannotReviseRecreatedActivity() {
        SettingsBoundary.configured = AppSettings(inputLanguage = "es", targetLanguage = "en")
        val old = startMic("old-microphone-mt", "La linterna azul guarda nuestro secreto", 0)
        val oldText = old.owner.value.findViewById<TextView>(R.id.captionText)
        old.owner.destroy()
        assertTrue("destroy must cancel the actual microphone translation caller", old.asr.isCancelled)
        assertPendingSdkRetained(old.client)
        assertNull(field(old.owner.value, "translation"))
        val oldLines = (field(old.owner.value, "lines") as List<*>).toList()
        val oldVisible = oldText.text.toString()

        val fresh = startMic("recreated-microphone-mt", "El velero plateado llega al puerto nuevo", 1)
        val freshText = fresh.owner.value.findViewById<TextView>(R.id.captionText)
        val freshVisible = freshText.text.toString()
        val freshLines = (field(fresh.owner.value, "lines") as List<*>).toList()
        old.client.complete("The retired microphone translation must never appear.")
        drainMainUntil("retired microphone SDK completion must close its client once") {
            old.client.closes.get() == 1 && old.asr.isCompleted
        }
        assertEquals("late task cannot revise destroyed activity captions", oldVisible, oldText.text.toString())
        assertEquals(oldLines, field(old.owner.value, "lines"))
        assertEquals("late task cannot revise recreated activity captions", freshVisible, freshText.text.toString())
        assertEquals(freshLines, field(fresh.owner.value, "lines"))
        assertFalse("new activity's actual translation caller must remain active", fresh.asr.isCancelled)
        assertPendingSdkRetained(fresh.client)
        assertSame(fresh.engine, field(fresh.owner.value, "engine"))
        assertEquals(1, old.engine.releases.get())
        assertEquals(0, fresh.engine.releases.get())

        val translated = "The silver sailboat reaches the new harbor."
        fresh.client.complete(translated)
        drainMainUntil("recreated microphone owner must publish its own translation") {
            fresh.asr.isCompleted && freshText.text.toString() == translated
        }
        assertEquals(listOf(translated), field(fresh.owner.value, "lines"))
        assertEquals(0, fresh.client.closes.get())
        fresh.owner.destroy()
        old.owner.destroy()
        assertEquals(1, old.client.closes.get())
        assertEquals(1, fresh.client.closes.get())
        assertEquals(1, old.engine.releases.get())
        assertEquals(1, fresh.engine.releases.get())
    }
}
