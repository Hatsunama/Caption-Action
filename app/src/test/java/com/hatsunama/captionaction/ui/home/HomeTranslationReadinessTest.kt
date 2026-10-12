package com.hatsunama.captionaction.ui.home

import android.os.Looper
import android.widget.TextView
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelIdentity
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.MlKitTaskBoundary
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import java.util.concurrent.TimeUnit
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

/** Real Home collectors, renderer and ML Kit engine; only SDK Tasks are controlled. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = CaptionActionApp::class,
    shadows = [HomeTranslationReadinessTest.ProgressDelivery::class, MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class, MlKitTaskBoundary.ModelManager::class],
    instrumentedPackages = ["com.google.mlkit.nl.translate.Translation", "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"])
class HomeTranslationReadinessTest {
    private lateinit var controller: ActivityController<HomeActivity>
    private val app get() = (RuntimeEnvironment.getApplication() as CaptionActionApp)
    private val home get() = controller.get()
    private val status get() = home.findViewById<TextView>(R.id.statusText).text.toString()
    private val pending = mutableListOf<TaskCompletionSource<Void>>()
    private val common get() = MlKitTranslationEngine.COMMON_SOURCES.map { TranslateRemoteModel.Builder(it).build() }.toSet()
    private var destroyed = false

    @Before fun setup() {
        ProgressDelivery.defer = false
        ProgressDelivery.queued.clear()
        MlKitTaskBoundary.reset()
        MlKitTaskBoundary.downloadedTask = Tasks.forResult(common)
        runBlocking { app.settings.update { it.copy(inputLanguage = "en", targetLanguage = "en", dualSubtitles = false) } }
        controller = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        until("Home must collect initial settings") { status.contains("ASR-only") }
    }
    @After fun cleanup() {
        if (::controller.isInitialized && !destroyed) controller.pause().stop().destroy()
        pending.forEach { it.trySetResult(null) }
        MlKitTaskBoundary.unblock()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun until(message: String, drain: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition()) {
            if (drain) shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() >= deadline) fail("$message; status=$status; requests=${MlKitTaskBoundary.downloadLanguages}")
            Thread.yield()
        }
    }
    private fun hold(language: String) = TaskCompletionSource<Void>().also {
        pending += it; MlKitTaskBoundary.downloadsByLanguage[language] = it.task
    }
    private fun select(target: String, input: String = "en") {
        runBlocking { app.settings.update { it.copy(inputLanguage = input, targetLanguage = target) } }
        until("Home must render current target $target") { status.contains(" · Input: ${input.trim().lowercase()} · Target: $target · MT:") }
    }
    private fun requested(language: String) = until("real engine must request $language") {
        language in MlKitTaskBoundary.downloadLanguages
    }
    private fun ready() = until("current request must finish ready") { status.endsWith(app.getString(R.string.translation_pack_ready)) }

    @Test fun unknownPackInventoryCannotDefaultToReady() {
        val inventory = TaskCompletionSource<Set<TranslateRemoteModel>>()
        MlKitTaskBoundary.downloadedTask = inventory.task
        try {
            select("pl")
            assertFalse("unverified packs cannot claim ready", status.contains("packs ready"))
        } finally { inventory.trySetResult(common) }
    }

    @Test fun changedTargetMustPrepareItsOwnNonCommonPack() {
        val polish = hold("pl"); val dutch = hold("nl")
        select("pl"); requested("pl")
        select("nl")
        polish.setResult(null)
        requested("nl")
        assertFalse("Polish readiness must never be relabeled Dutch", status.contains("packs ready"))
        assertFalse(dutch.task.isComplete)
        dutch.setResult(null); ready()
        assertEquals(listOf("pl", "nl"), MlKitTaskBoundary.downloadLanguages.toList())
    }

    @Test fun onlyLatestPendingRequestRunsAfterActivePreparation() {
        val polish = hold("pl"); val ukrainian = hold("uk")
        select("pl"); requested("pl")
        select("nl"); select("uk")
        polish.setResult(null); requested("uk")
        assertEquals(listOf("pl", "uk"), MlKitTaskBoundary.downloadLanguages.toList())
        assertFalse(status.contains("packs ready"))
        ukrainian.setResult(null); ready()
    }

    @Test fun oldCompletionPreservesAsrOnlyAndPartialModelStatus() {
        ModelCache(app).partFileFor(ModelTier.BALANCED).writeText("partial")
        val polish = hold("pl")
        select("pl"); requested("pl")
        select("en")
        val before = status
        polish.setResult(null)
        awaitPreparationFinished()
        assertEquals("obsolete final result cannot rewrite ASR-only or partial model status", before, status)
        assertTrue(status.contains("partial (can resume)"))
        assertTrue(status.contains("Dual: unavailable"))
    }

    @Test fun oldCompletionPreservesUnsupportedTarget() {
        val polish = hold("pl")
        select("pl"); requested("pl"); select("xx")
        val before = status
        polish.setResult(null); awaitPreparationFinished()
        assertEquals(before, status)
        assertTrue(status.contains("does not support target xx"))
    }

    @Test fun queuedProgressCannotMutateDestroyedHome() {
        val inventory = TaskCompletionSource<Set<TranslateRemoteModel>>()
        MlKitTaskBoundary.downloadedTask = inventory.task
        hold("pl")
        select("pl")
        inventory.setResult(common)
        until("SDK download confirms progress callback was queued", drain = false) {
            "pl" in MlKitTaskBoundary.downloadLanguages
        }
        controller.pause().stop().destroy(); destroyed = true
        val before = status
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("queued old progress must respect destroyed owner", before, status)
    }

    @Test fun failedPackCanRecoverWhenSettingsRequestItAgain() {
        val polish = hold("pl")
        select("pl"); requested("pl")
        polish.setException(IllegalStateException("offline fixture"))
        until("failure must be shown") { status.contains("Translation pack issue:") }
        select("en")
        val retry = hold("pl")
        select("pl")
        until("returning to failed target retries") { MlKitTaskBoundary.downloadLanguages.count { it == "pl" } == 2 }
        assertFalse(status.contains("packs ready"))
        retry.setResult(null); ready()
    }

    @Test fun lateProgressCannotReplaceAsrOnlyStatus() {
        assertLateProgressPreserves("en")
    }

    @Test fun lateProgressCannotReplaceUnsupportedStatus() {
        assertLateProgressPreserves("xx")
    }

    private fun assertLateProgressPreserves(target: String) {
        val inventory = TaskCompletionSource<Set<TranslateRemoteModel>>()
        MlKitTaskBoundary.downloadedTask = inventory.task
        val polish = hold("pl")
        try {
            select("pl")
            until("old request must enter inventory query") { MlKitTaskBoundary.downloadedEntered.latch.count == 0L }
            select(target)
            val before = status
            inventory.setResult(common)
            requested("pl")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("obsolete progress must not replace current non-MT status", before, status)
            polish.setResult(null); awaitPreparationFinished()
            assertEquals(before, status)
        } finally { inventory.trySetResult(common) }
    }

    @Test fun changingSourcePreparesItsOwnNonCommonPack() {
        val polish = hold("pl"); val dutch = hold("nl")
        select("en", "pl"); requested("pl")
        select("en", "nl")
        polish.setResult(null); requested("nl")
        assertFalse(status.contains("packs ready"))
        dutch.setResult(null); ready()
        assertEquals(listOf("pl", "nl"), MlKitTaskBoundary.downloadLanguages.toList())
    }

    @Test fun normalizedAndUnrelatedSettingsDoNotDuplicatePreparation() {
        val polish = hold("pl")
        select("pl"); requested("pl")
        select(" PL ")
        runBlocking { app.settings.update { it.copy(saveSubtitlesToFile = true) } }
        shadowOf(Looper.getMainLooper()).idle()
        polish.setResult(null); ready(); awaitPreparationFinished()
        assertEquals(listOf("pl"), MlKitTaskBoundary.downloadLanguages.toList())
    }

    @Test fun obsoleteFailureDoesNotPoisonLatestRequest() {
        val polish = hold("pl"); val dutch = hold("nl")
        select("pl"); requested("pl"); select("nl")
        polish.setException(IllegalStateException("old offline fixture"))
        requested("nl")
        assertFalse(status.contains("old offline fixture"))
        assertFalse(status.contains("packs ready"))
        dutch.setResult(null); ready()
    }

    @Test fun returningToSamePairAfterAsrOnlyDoesNotReviveOldGeneration() {
        val oldPolish = hold("pl")
        select("pl"); requested("pl"); select("en"); select("pl")
        val newPolish = hold("pl")
        oldPolish.setResult(null)
        until("same identity with newer generation must prepare again") {
            MlKitTaskBoundary.downloadLanguages.count { it == "pl" } == 2
        }
        assertFalse(status.contains("packs ready"))
        newPolish.setResult(null); ready()
    }

    @org.robolectric.annotation.Implements(android.app.Activity::class)
    class ProgressDelivery : org.robolectric.shadows.ShadowActivity() {
        @org.robolectric.annotation.Implementation
        override fun runOnUiThread(action: Runnable) {
            if (defer && realActivity is HomeActivity) queued += action
            else super.runOnUiThread(action)
        }
        companion object {
            @Volatile var defer = false
            val queued = java.util.concurrent.CopyOnWriteArrayList<Runnable>()
        }
    }

    @Test fun delayedSameGenerationProgressCannotDowngradeReady() {
        assertTerminalStatusSurvivesDelayedProgress(failDownload = false)
    }

    @Test fun delayedSameGenerationProgressCannotDowngradeFailure() {
        assertTerminalStatusSurvivesDelayedProgress(failDownload = true)
    }

    private fun assertTerminalStatusSurvivesDelayedProgress(failDownload: Boolean) {
        ProgressDelivery.defer = true
        val polish = hold("pl")
        select("pl"); requested("pl")
        if (failDownload) polish.setException(IllegalStateException("offline fixture"))
        else polish.setResult(null)
        awaitPreparationFinished()
        val before = status
        assertTrue(if (failDownload) before.contains("Translation pack issue:") else before.endsWith(app.getString(R.string.translation_pack_ready)))
        assertTrue("real engine must have queued progress", ProgressDelivery.queued.isNotEmpty())
        ProgressDelivery.queued.forEach { it.run() }
        assertEquals("completed job must reject even same-generation delayed progress", before, status)
    }

    @Test fun explicitStartRetriesFailedPreparationWithoutWaitingForIt() {
        val polish = hold("pl")
        select("pl"); requested("pl")
        polish.setException(IllegalStateException("offline fixture"))
        until("failure must be shown") { status.contains("Translation pack issue:") }
        awaitPreparationFinished()
        val retry = hold("pl")
        // Small real-byte integrity fixture for the actual Home model gate; MT stays real.
        val modelBytes = ByteArray(8193) { (it * 17).toByte() }
        val modelDigest = java.security.MessageDigest.getInstance("SHA-256").digest(modelBytes)
            .joinToString("") { "%02x".format(it) }
        val cache = ModelCache(app, { ModelIdentity(modelBytes.size.toLong(), modelDigest) })
        cache.fileFor(ModelTier.BALANCED).writeBytes(modelBytes)
        HomeActivity::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(home, cache)
        home.findViewById<android.view.View>(R.id.btnStartLive).performClick()
        val field = HomeActivity::class.java.getDeclaredField("modelGateDialog").apply { isAccessible = true }
        val gate = field.get(home) as androidx.appcompat.app.AlertDialog
        until("actual cache verification must enable Use") { gate.findViewById<android.view.View>(R.id.btnUseAndStart)!!.isEnabled }
        gate.findViewById<android.view.View>(R.id.btnUseAndStart)!!.performClick()
        until("Start must retry failed MT preparation") { MlKitTaskBoundary.downloadLanguages.count { it == "pl" } == 2 }
        assertFalse("MT retry remains pending without blocking permission handoff", retry.task.isComplete)
        assertNotNull(shadowOf(home).peekNextStartedActivityForResult())
        retry.setResult(null); ready()
    }

    private fun awaitPreparationFinished() {
        val field = HomeActivity::class.java.getDeclaredField("mtPrepareJob").apply { isAccessible = true }
        until("preparation must complete") { (field.get(home) as? kotlinx.coroutines.Job)?.isActive != true }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
