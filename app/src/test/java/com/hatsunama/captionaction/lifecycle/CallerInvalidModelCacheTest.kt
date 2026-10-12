package com.hatsunama.captionaction.lifecycle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.InferenceEngine
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.CaptureBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.SettingsBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.TranslationBoundary
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.ui.home.HomeActivity
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * Real Service/Mic preparation, real ModelCache and the production Balanced identity.
 * Deliberately no ModelAvailabilityBoundary or other ModelCache shadow. Files are sparse,
 * zero-filled invalid fixtures, never downloaded models. The hardware, settings, factory
 * and ML Kit boundaries prevent microphone/projection/network/native activity even if a
 * regression reaches them. The fake engine returns failure if unexpectedly loaded, so a
 * broken readiness gate cannot start a consumer or hang cleanup.
 *
 * These four tests also compile against the pre-integrity caller/cache APIs: the fixture
 * size is pinned below rather than obtained from a newly added ModelTier field. That lets
 * the same assertions identify the former size-only bug at the actual factory boundary.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(
    sdk = [35],
    application = CaptionActionApp::class,
    shadows = [
        CallerInvalidModelCacheTest.RejectingFactoryBoundary::class,
        SettingsBoundary::class,
        CaptureBoundary::class,
        TranslationBoundary::class,
        CallerPreparationOwnershipTest.ProjectionManagerBoundary::class,
        CallerPreparationOwnershipTest.ProjectionBoundary::class,
        CallerPreparationOwnershipTest.ImageReaderBoundary::class
    ],
    instrumentedPackages = [
        "com.hatsunama.captionaction.inference.InferenceEngineFactory",
        "com.hatsunama.captionaction.inference.MlKitTranslationEngine",
        "com.hatsunama.captionaction.data.SettingsRepository",
        "com.hatsunama.captionaction.audio.AudioCapture"
    ]
)
class CallerInvalidModelCacheTest {
    @Implements(InferenceEngineFactory::class)
    class RejectingFactoryBoundary {
        @Implementation
        fun create(context: Context, tier: ModelTier, targetLanguage: String): InferenceEngine {
            creations.incrementAndGet()
            return object : InferenceEngine {
                override val name: String = "invalid-cache-sentinel"
                override suspend fun load(modelFile: File): Boolean {
                    loads.incrementAndGet()
                    return false
                }
                override suspend fun transcribeWindow(
                    pcm16le: ShortArray, sampleRateHz: Int, forceFlush: Boolean
                ): CaptionResult? {
                    inferences.incrementAndGet()
                    return null
                }
                override fun release() { releases.incrementAndGet() }
            }
        }

        companion object {
            val creations = AtomicInteger()
            val loads = AtomicInteger()
            val inferences = AtomicInteger()
            val releases = AtomicInteger()
            fun reset() {
                creations.set(0)
                loads.set(0)
                inferences.set(0)
                releases.set(0)
            }
        }
    }

    private val services = mutableListOf<ServiceController<CaptionOverlayService>>()
    private val activities = mutableListOf<ActivityController<MicTranslatorActivity>>()
    private val ownerJobs = mutableListOf<Job>()
    private lateinit var cache: ModelCache

    @Before fun resetFixture() {
        RejectingFactoryBoundary.reset()
        SettingsBoundary.configured = AppSettings(modelTierId = TIER.id, saveSubtitlesToFile = false)
        CaptureBoundary.nextDrain.set(null)
        CaptureBoundary.extraDrains.clear()
        TranslationBoundary.constructions.set(0)
        TranslationBoundary.failureMessage = null
        TranslationBoundary.translatedOutput = null
        TranslationBoundary.translationGate = null
        ShadowToast.reset()
        cache = ModelCache(RuntimeEnvironment.getApplication())
        // Robolectric application files only. Each test owns exactly these two paths.
        cache.fileFor(TIER).delete()
        cache.partFileFor(TIER).delete()
    }

    @After fun cleanup() {
        services.forEach { it.destroy() }
        activities.forEach { it.destroy() }
        drainMainUntil("all actual owner work terminates during cleanup") {
            ownerJobs.all { root -> root.children.none() }
        }
        if (::cache.isInitialized) {
            cache.fileFor(TIER).delete()
            cache.partFileFor(TIER).delete()
        }
    }

    @Test fun serviceRejectsOldThresholdSizedInvalidFinalBeforeFactoryOrLoad() {
        assertServiceRejects(OLD_READY_THRESHOLD)
    }

    @Test fun serviceRejectsExactSizeWrongDigestFinalBeforeFactoryOrLoad() {
        assertServiceRejects(EXACT_BALANCED_BYTES)
    }

    @Test fun micRejectsOldThresholdSizedInvalidFinalBeforeFactoryOrLoad() {
        assertMicRejects(OLD_READY_THRESHOLD)
    }

    @Test fun micRejectsExactSizeWrongDigestFinalBeforeFactoryOrLoad() {
        assertMicRejects(EXACT_BALANCED_BYTES)
    }

    private fun assertServiceRejects(bytes: Long) {
        seedInvalidFinal(bytes)
        val controller = Robolectric.buildService(CaptionOverlayService::class.java).create()
            .also { services += it }
        val owner = controller.get()
        val root = (field(owner, "scope") as CoroutineScope).coroutineContext[Job]!!
            .also { ownerJobs += it }
        val result = owner.onStartCommand(Intent().apply {
            action = CaptionOverlayService.ACTION_START
            putExtra(CaptionOverlayService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(CaptionOverlayService.EXTRA_RESULT_DATA, Intent("invalid-cache-projection-fixture"))
        }, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        drainMainUntil("Service preparation must fail and finish all its child work") {
            field(owner, "tearingDown") == true && root.children.none()
        }

        assertNoInferenceOrTranslation(owner)
        assertEquals(owner.getString(R.string.error_model), ShadowToast.getTextOfLatestToast())
        assertTrue("invalid model must stop the service", shadowOf(owner).isStoppedBySelf)
        assertNull("failed preparation must remove its overlay", field(owner, "overlayView"))
        assertNull("failed preparation must release projection", field(owner, "mediaProjection"))
        assertEquals(HomeActivity::class.java.name, shadowOf(owner).nextStartedActivity.component?.className)
        assertStillUntrusted(bytes)
    }

    private fun assertMicRejects(bytes: Long) {
        seedInvalidFinal(bytes)
        val controller = Robolectric.buildActivity(MicTranslatorActivity::class.java).create()
            .also { activities += it }
        val owner = controller.get()
        val root = owner.lifecycleScope.coroutineContext[Job]!!.also { ownerJobs += it }
        drainMainUntil("Mic preparation must finish the activity without publishing readiness") {
            owner.isFinishing && root.children.none()
        }

        assertNoInferenceOrTranslation(owner)
        assertEquals(owner.getString(R.string.error_model), ShadowToast.getTextOfLatestToast())
        assertEquals("Mic must not publish engine readiness", false, field(owner, "engineReady"))
        assertEquals("Mic must not begin listening", false, field(owner, "micOn"))
        assertStillUntrusted(bytes)
    }

    private fun seedInvalidFinal(bytes: Long) {
        assertTrue("fixture must pass the old size-only readiness gate", bytes >= OLD_READY_THRESHOLD)
        RandomAccessFile(cache.fileFor(TIER), "rw").use { it.setLength(bytes) }
        assertEquals(bytes, cache.fileFor(TIER).length())
    }

    private fun assertNoInferenceOrTranslation(owner: Any) {
        assertEquals("invalid final must be rejected before inference factory creation", 0, RejectingFactoryBoundary.creations.get())
        assertEquals("invalid bytes must never reach an engine load", 0, RejectingFactoryBoundary.loads.get())
        assertEquals(0, RejectingFactoryBoundary.inferences.get())
        assertEquals("no engine should exist even to be released", 0, RejectingFactoryBoundary.releases.get())
        assertEquals("invalid final must not construct translation resources", 0, TranslationBoundary.constructions.get())
        assertNull("invalid final must not publish an engine", field(owner, "engine"))
        assertNull("invalid final must not publish translation", field(owner, "translation"))
        assertNull("invalid final must not start an ASR consumer", field(owner, "asrJob"))
    }

    private fun assertStillUntrusted(bytes: Long) {
        assertFalse("rejected bytes must not acquire a ready token", cache.isReadyForAsr(TIER))
        assertFalse(cache.isPresent(TIER))
        assertEquals("preparation must not replace the invalid fixture", bytes, cache.fileFor(TIER).length())
        assertFalse("preparation must not start a partial model download", cache.partFileFor(TIER).exists())
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
        private val OLD_READY_THRESHOLD = maxOf(1_000_000L, (TIER.approxBytes * 9L) / 10L)
        // Approved ggml-tiny-q5_1.bin identity, docs/model-artifact-identities.md.
        // Same size, all-zero contents: the production SHA-256 verifier must reject it.
        private const val EXACT_BALANCED_BYTES = 32_152_673L
    }
}
