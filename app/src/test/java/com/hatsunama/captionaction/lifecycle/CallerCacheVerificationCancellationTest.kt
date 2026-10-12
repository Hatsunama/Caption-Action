package com.hatsunama.captionaction.lifecycle

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelFileOperations
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.data.SubtitleFileRecorder
import com.hatsunama.captionaction.lifecycle.CallerInvalidModelCacheTest.RejectingFactoryBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.CaptureBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.SettingsBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.TranslationBoundary
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

/**
 * Cancellation controls for actual Service/Mic preparation and actual ModelCache.verify.
 * The only cache-adjacent shadow wraps ModelFileOperations.openRead with a genuine file
 * stream. It lets one buffer reach the real SHA-256 digest, then pauses the second real
 * read before returning its bytes. Cancellation must prevent a third read and close the
 * stream when that in-flight read returns. There is no readiness, identity, digest,
 * ModelCache, or caller-method shadow and no valid/downloaded model or native execution.
 *
 * These are new-boundary cancellation controls, not old-cache counterfactual red tests.
 * The sparse zero-filled file has the production Balanced size and the wrong digest.
 * Its rejection without cancellation is covered separately by CallerInvalidModelCacheTest.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    sdk = [35],
    application = CaptionActionApp::class,
    shadows = [
        CallerCacheVerificationCancellationTest.FileReadBoundary::class,
        RejectingFactoryBoundary::class,
        SettingsBoundary::class,
        CaptureBoundary::class,
        TranslationBoundary::class,
        CallerPreparationOwnershipTest.ProjectionManagerBoundary::class,
        CallerPreparationOwnershipTest.ProjectionBoundary::class,
        CallerPreparationOwnershipTest.ImageReaderBoundary::class
    ],
    instrumentedPackages = [
        "com.hatsunama.captionaction.data.ModelFileOperations",
        "com.hatsunama.captionaction.inference.InferenceEngineFactory",
        "com.hatsunama.captionaction.inference.MlKitTranslationEngine",
        "com.hatsunama.captionaction.data.SettingsRepository",
        "com.hatsunama.captionaction.audio.AudioCapture"
    ]
)
class CallerCacheVerificationCancellationTest {
    class ControlledRead(val file: File) {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val opens = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val bytesRead = AtomicLong()
        @Volatile var readerThread: Thread? = null

        fun wrap(input: InputStream): InputStream = object : FilterInputStream(input) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = super.read(buffer, offset, length)
                if (n > 0) bytesRead.addAndGet(n.toLong())
                if (reads.incrementAndGet() == 2) {
                    // First buffer has already returned to ModelCache's real digest loop.
                    check(n > 0) { "Expected an in-flight real model-file read" }
                    readerThread = Thread.currentThread()
                    entered.countDown()
                    check(proceed.await(10, TimeUnit.SECONDS)) { "Model hash read was never released" }
                }
                return n
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    closes.incrementAndGet()
                    closed.countDown()
                }
            }
        }

        fun allow() { proceed.countDown() }
    }

    @Implements(value = ModelFileOperations::class, isInAndroidSdk = false)
    class FileReadBoundary {
        @Implementation
        fun openRead(file: File): InputStream {
            val operation = checkNotNull(planned.get()) { "Unexpected model-file read" }
            check(file.canonicalFile == operation.file.canonicalFile) { "Unexpected model-file path" }
            operation.opens.incrementAndGet()
            // Same real opening as the production method; only read timing is controlled.
            return operation.wrap(file.inputStream())
        }

        companion object { val planned = AtomicReference<ControlledRead?>() }
    }

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

    private val services = mutableListOf<ServiceOwner>()
    private val microphones = mutableListOf<MicOwner>()
    private val roots = mutableListOf<Job>()
    private val preparations = mutableListOf<Job>()
    private lateinit var cache: ModelCache
    private lateinit var read: ControlledRead

    @Before fun resetFixture() {
        RejectingFactoryBoundary.reset()
        SettingsBoundary.configured = AppSettings(modelTierId = TIER.id, saveSubtitlesToFile = false)
        CaptureBoundary.nextDrain.set(null)
        CaptureBoundary.extraDrains.clear()
        TranslationBoundary.constructions.set(0)
        TranslationBoundary.failureMessage = null
        TranslationBoundary.translatedOutput = null
        TranslationBoundary.translationGate = null
        FileReadBoundary.planned.set(null)
        ShadowToast.reset()
        cache = ModelCache(RuntimeEnvironment.getApplication())
        // These are only the fixture's two paths in Robolectric application storage.
        cache.fileFor(TIER).delete()
        cache.partFileFor(TIER).delete()
        assertFalse(cache.fileFor(TIER).exists())
        assertFalse(cache.partFileFor(TIER).exists())
        assertFalse("missing fixture must clear any prior metadata snapshot", cache.isPresent(TIER))
        RandomAccessFile(cache.fileFor(TIER), "rw").use { it.setLength(TIER.expectedBytes) }
        assertEquals(TIER.expectedBytes, cache.fileFor(TIER).length())
        assertFalse("size alone must not grant readiness", cache.isReadyForAsr(TIER))
        read = ControlledRead(cache.fileFor(TIER))
        FileReadBoundary.planned.set(read)
    }

    @After fun cleanup() {
        // Always unblock IO, including after an assertion fails before the explicit release.
        if (::read.isInitialized) read.allow()
        services.forEach { it.destroy() }
        microphones.forEach { it.destroy() }
        drainMainUntil("all preparation and owner child jobs must terminate in cleanup") {
            preparations.all { it.isCompleted } && roots.all { it.isCompleted && it.children.none() }
        }
        FileReadBoundary.planned.set(null)
        if (::cache.isInitialized) {
            cache.fileFor(TIER).delete()
            cache.partFileFor(TIER).delete()
        }
    }

    @Test fun serviceStopDuringActualPreFactoryHashCancelsVerificationAndPublishesNothing() {
        val owner = ServiceOwner(Robolectric.buildService(CaptionOverlayService::class.java).create())
            .also { services += it }
        val service = owner.value
        val root = (field(service, "scope") as CoroutineScope).coroutineContext[Job]!!
            .also { roots += it }
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(Intent().apply {
            action = CaptionOverlayService.ACTION_START
            putExtra(CaptionOverlayService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptionOverlayService.EXTRA_RESULT_DATA, Intent("hash-cancellation-projection-fixture"))
        }, 0, 1))
        awaitBlockedHash()
        val preparation = (field(service, "preparationJob") as Job).also { preparations += it }
        assertSame("hash must belong to the actual Service preparation", preparation, root.children.single())
        assertBlockedPreparation(preparation)
        assertNoInferenceOrTranslation(service)
        assertFalse(cache.isReadyForAsr(TIER))

        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(
            Intent().setAction(CaptionOverlayService.ACTION_STOP), 0, 2
        ))
        assertTrue("actual STOP must cancel preparation while the read is blocked", preparation.isCancelled)
        assertFalse("the in-flight read must still own unfinished child work", preparation.isCompleted)
        assertEquals("lifecycle teardown must precede the read release", 1L, read.proceed.count)
        assertEquals(true, field(service, "tearingDown"))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(field(service, "preparationJob"))
        assertNull(field(service, "overlayView"))
        assertNull(field(service, "mediaProjection"))

        read.allow()
        awaitCompletedCancellation(preparation, root)
        assertNoInferenceOrTranslation(service)
        assertEquals("no caption may be published", 0L, field(service, "captionSeq"))
        assertEquals(false, field(service, "hasRealCaption"))
        assertEquals(-1L, field(service, "subtitleAppendSeq"))
        assertEquals(0L, field(service, "lastPublishedAtMs"))
        assertTrue("no MT readiness work may be scheduled", (field(service, "mtJobs") as Collection<*>).isEmpty())
        val recorder = field(service, "subtitleRecorder") as SubtitleFileRecorder
        assertFalse("no recording may start", recorder.isRecording)
        assertNull(recorder.currentPath)
        assertNull("cancelled verification must not report invalid-model failure", ShadowToast.getTextOfLatestToast())
        assertNull("cancelled verification must not send the user Home", shadowOf(service).nextStartedActivity)
        assertReadClosedAndCacheUntrusted()
    }

    @Test fun micDestroyDuringActualPreFactoryHashCancelsVerificationAndPublishesNothing() {
        val owner = MicOwner(Robolectric.buildActivity(MicTranslatorActivity::class.java).create())
            .also { microphones += it }
        val activity = owner.value
        val root = activity.lifecycleScope.coroutineContext[Job]!!.also { roots += it }
        awaitBlockedHash()
        val preparation = root.children.single().also { preparations += it }
        assertBlockedPreparation(preparation)
        assertNoInferenceOrTranslation(activity)
        assertEquals(false, field(activity, "engineReady"))
        assertFalse(cache.isReadyForAsr(TIER))

        owner.destroy()
        assertTrue(activity.isDestroyed)
        assertTrue("actual destroy must cancel the lifecycle scope", root.isCancelled)
        assertTrue("actual destroy must cancel the blocked preparation", preparation.isCancelled)
        assertFalse("the in-flight read must still own unfinished child work", preparation.isCompleted)
        assertEquals("lifecycle teardown must precede the read release", 1L, read.proceed.count)

        read.allow()
        awaitCompletedCancellation(preparation, root)
        assertTrue("destroyed lifecycle scope must finish after its IO child", root.isCompleted)
        assertNoInferenceOrTranslation(activity)
        assertEquals("cancelled Mic preparation must not publish readiness", false, field(activity, "engineReady"))
        assertEquals(false, field(activity, "micOn"))
        assertNull(field(activity, "micRun"))
        assertTrue("no caption may be published", (field(activity, "lines") as List<*>).isEmpty())
        assertNull("cancelled verification must not report invalid-model failure", ShadowToast.getTextOfLatestToast())
        assertReadClosedAndCacheUntrusted()
    }

    private fun awaitBlockedHash() {
        drainMainUntil("actual caller must reach the controlled second real hash read") { read.entered.count == 0L }
        assertNotSame("model hashing must run off Main", Looper.getMainLooper().thread, read.readerThread)
        assertEquals(1, read.opens.get())
        assertEquals(2, read.reads.get())
        assertEquals(2L * HASH_BUFFER_BYTES, read.bytesRead.get())
        assertEquals(1L, read.proceed.count)
        assertEquals(0, read.closes.get())
    }

    private fun assertBlockedPreparation(preparation: Job) {
        assertTrue(preparation.isActive)
        assertFalse(preparation.isCompleted)
        assertTrue("real verification IO must remain a structured preparation child", preparation.children.any())
    }

    private fun awaitCompletedCancellation(preparation: Job, root: Job) {
        drainMainUntil("cancelled actual preparation and its real verifier must finish") {
            preparation.isCompleted && root.children.none() &&
                (!root.isCancelled || root.isCompleted) && read.closed.count == 0L
        }
        assertTrue(preparation.isCancelled)
    }

    private fun assertNoInferenceOrTranslation(owner: Any) {
        assertEquals("cancelled hash must never reach the factory", 0, RejectingFactoryBoundary.creations.get())
        assertEquals("cancelled hash must never reach native load", 0, RejectingFactoryBoundary.loads.get())
        assertEquals(0, RejectingFactoryBoundary.inferences.get())
        assertEquals("there must be no created engine even to release", 0, RejectingFactoryBoundary.releases.get())
        assertEquals("cancelled hash must not construct MT resources", 0, TranslationBoundary.constructions.get())
        assertNull(field(owner, "engine"))
        assertNull(field(owner, "translation"))
        assertNull("no ASR consumer may start", field(owner, "asrJob"))
    }

    private fun assertReadClosedAndCacheUntrusted() {
        assertEquals("cancellation must stop hashing before a third read", 2, read.reads.get())
        assertEquals("only the two real buffers may have been read", 2L * HASH_BUFFER_BYTES, read.bytesRead.get())
        assertEquals("no second verification/load is allowed", 1, read.opens.get())
        assertEquals("cancellation must close the actual file stream exactly once", 1, read.closes.get())
        assertFalse("cancelled verification must not grant a ready token", cache.isReadyForAsr(TIER))
        assertFalse(cache.isPresent(TIER))
        assertEquals("verification must leave the fixture in place", TIER.expectedBytes, cache.fileFor(TIER).length())
        assertFalse("caller preparation must not begin a model download", cache.partFileFor(TIER).exists())
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun drainMainUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(message, condition())
    }

    companion object {
        private val TIER = ModelTier.BALANCED
        private const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
