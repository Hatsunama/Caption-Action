package com.hatsunama.captionaction.lifecycle

import android.content.Context
import android.os.Looper
import android.widget.NumberPicker
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.MlKitTaskBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledDrain
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledEngine
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.ControlledInference
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.FactoryBoundary
import com.hatsunama.captionaction.lifecycle.CallerPreparationOwnershipTest.SettingsBoundary
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import com.hatsunama.captionaction.util.Languages
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = CaptionActionApp::class, shadows = [
    FactoryBoundary::class, SettingsBoundary::class,
    CallerPreparationOwnershipTest.ModelAvailabilityBoundary::class, RecoveryCaptureBoundary::class,
    MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class,
    MlKitTaskBoundary.ModelManager::class
], instrumentedPackages = [
    "com.hatsunama.captionaction.ui.mic",
    "com.hatsunama.captionaction.inference.InferenceEngineFactory",
    "com.hatsunama.captionaction.data.SettingsRepository", "com.hatsunama.captionaction.data.ModelCache",
    "com.hatsunama.captionaction.audio.AudioCapture", "com.google.mlkit.nl.translate.Translation",
    "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"
])
class MicPublicationLifetimeTest {
    private lateinit var controller: ActivityController<MicTranslatorActivity>
    private lateinit var engine: ControlledEngine
    private val drains = mutableListOf<ControlledDrain>()
    private val inferences = mutableListOf<ControlledInference>()
    private val jobs = mutableListOf<Job>()
    private lateinit var waiting: ControlledDrain
    private val activity get() = controller.get()
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun lines(): List<*> = (field("lines") as List<*>).toList()
    private fun await(message: String, main: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            if (main) shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(message, condition())
    }
    @Before fun setup() {
        RecoveryCaptureBoundary.reset(); FactoryBoundary.planned.clear(); MlKitTaskBoundary.reset()
        SettingsBoundary.configured = AppSettings(inputLanguage = "en", targetLanguage = "en")
        RuntimeEnvironment.getApplication().getSharedPreferences("mic_translator", Context.MODE_PRIVATE)
            .edit().clear().putString("mic_from", "en").putString("mic_to", "en").commit()
        ShadowSystemClock.advanceBy(1000, TimeUnit.MILLISECONDS)
    }
    @After fun cleanup() {
        if (::controller.isInitialized) {
            jobs += activity.lifecycleScope.coroutineContext[Job]!!.children.toList()
            controller.destroy()
        }
        drains.forEach { it.allow() }; inferences.forEach { it.allow() }
        if (::engine.isInitialized) engine.allow()
        MlKitTaskBoundary.unblock()
        await("all actual activity jobs terminate") { MlKitTaskBoundary.unblock(); jobs.all { it.isCompleted } }
    }
    private fun newDrain(): ControlledDrain = ControlledDrain(ShortArray(28_000) { 710 }).also {
        drains += it; RecoveryCaptureBoundary.drains.add(it)
    }
    private fun create(from: String = "en", to: String = "en") {
        RuntimeEnvironment.getApplication().getSharedPreferences("mic_translator", Context.MODE_PRIVATE)
            .edit().putString("mic_from", from).putString("mic_to", to).commit()
        engine = ControlledEngine("mic-publication").also { FactoryBoundary.planned.add(it) }
        controller = Robolectric.buildActivity(MicTranslatorActivity::class.java).create()
        await("actual engine load starts") { engine.entered.count == 0L }
        engine.allow()
        await("actual preparation commits") { field("engineReady") == true }
        start()
    }
    private fun start() {
        waiting = newDrain()
        assertTrue(activity.findViewById<MaterialButton>(R.id.btnMic).performClick())
        jobs += field("asrJob") as Job
        await("actual Mic loop reaches next FIFO window") { waiting.entered.count == 0L }
    }
    private fun stop() { assertTrue(activity.findViewById<MaterialButton>(R.id.btnMic).performClick()) }
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(ms, TimeUnit.MILLISECONDS) }
    private fun prepare(text: String, allowInference: Boolean = true): Pair<ControlledInference, ControlledDrain> {
        val inference = ControlledInference(CaptionResult(text, "en", 1f, 0L, 1000L)).also {
            inferences += it; if (allowInference) it.allow()
        }
        engine.inference = inference
        val next = newDrain()
        waiting.allow()
        waiting = next
        return inference to next
    }
    private fun emit(text: String, elapsed: Long = 0L) {
        advance(elapsed)
        prepare(text)
        await("actual Mic loop finishes source publication or suppression") { waiting.entered.count == 0L }
    }
    private fun select(id: Int, code: String) {
        val picker = activity.findViewById<NumberPicker>(id)
        val old = picker.value
        val value = Languages.all.indexOfFirst { it.code == code }
        picker.value = value
        val listener = ReflectionHelpers.getField<NumberPicker.OnValueChangeListener>(picker, "mOnValueChangeListener")
        listener.onValueChange(picker, old, value)
    }
    @Test fun identicalPhraseSuppressesBeforeGapAndAppendsAtExactGap() {
        create(); val phrase = "The silver lantern guards the harbor"
        emit(phrase); assertEquals(listOf(phrase), lines())
        emit(phrase, 1499); assertEquals("same utterance is suppressed", listOf(phrase), lines())
        emit(phrase, 1); assertEquals("same speech is valid again at 1500 ms", listOf(phrase, phrase), lines())
    }
    @Test fun elapsedQuietGapExpiresWhenWallClockStandsStill() {
        create(); val phrase = "The silver lantern guards the harbor"
        emit(phrase)
        val wallBefore = org.robolectric.shadows.ShadowSystem.currentTimeMillis()
        val elapsedBefore = android.os.SystemClock.elapsedRealtime()
        // Controlled clock boundary: elapsed time advances while the instrumented wall
        // clock remains fixed, as if a wall-clock correction canceled the elapsed gap.
        ShadowSystemClock.simulateDeepSleep(java.time.Duration.ofMillis(1500))
        assertEquals(wallBefore, org.robolectric.shadows.ShadowSystem.currentTimeMillis())
        assertEquals(elapsedBefore + 1500, android.os.SystemClock.elapsedRealtime())
        emit(phrase)
        assertEquals("duration policy must follow elapsed time, not wall-clock corrections", listOf(phrase, phrase), lines())
    }
    @Test fun micStopAndRestartDoesNotSuppressTheSamePhrase() {
        create(); val phrase = "The silver lantern guards the harbor"
        emit(phrase); val oldWaiting = waiting; stop(); oldWaiting.allow()
        await("old consumer stops") { jobs.last().isCompleted }
        start(); emit(phrase)
        assertEquals("new mic run appends even without a quiet gap", listOf(phrase, phrase), lines())
    }
    @Test fun fromChangeStartsANewPairAtTheNextWindow() {
        MlKitTaskBoundary.translatorImmediate = "A completed pair translation"
        create(); val phrase = "La linterna azul protege nuestro puerto"
        emit(phrase); select(R.id.pickerFrom, "es"); emit(phrase)
        assertEquals("same text must invoke translation for the new From pair", 1, MlKitTaskBoundary.translators.sumOf { it.tasks.size })
        assertEquals(2, lines().size)
    }
    @Test fun toChangeStartsANewPairAtTheNextWindow() {
        MlKitTaskBoundary.translatorImmediate = "Una traduccion completa del puerto"
        create(); val phrase = "The silver lantern guards the harbor"
        emit(phrase); select(R.id.pickerTo, "es"); emit(phrase)
        assertEquals("same text must invoke translation for the new To pair", 1, MlKitTaskBoundary.translators.sumOf { it.tasks.size })
        assertEquals(2, lines().size)
    }
    @Test fun canceledPublicationCannotPoisonRestartDedupe() {
        create(); val phrase = "The silver lantern guards the harbor"
        val (inference, pending) = prepare(phrase, allowInference = false)
        await("native result is held") { inference.entered.count == 0L }
        val oldJob = field("asrJob") as Job
        // Main stays paused: allow native work to queue its publication, then cancel via real click.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(Looper.getMainLooper()).isIdle)
        inference.allow()
        await("Main publication is queued", main = false) { !shadowOf(Looper.getMainLooper()).isIdle }
        // Observe only state: no synthetic owner method or publication path is substituted.
        val publicationOwner = try { field("micRun")!! } catch (_: NoSuchFieldException) { activity }
        val unpublishedNorm = publicationOwner.javaClass.getDeclaredField("lastPublishedNorm")
            .apply { isAccessible = true }.get(publicationOwner)
        assertEquals("queued Main work must not commit dedupe before its line is published", "", unpublishedNorm)
        stop(); pending.allow(); RecoveryCaptureBoundary.drains.remove(pending)
        await("cancelled caller completes") { oldJob.isCompleted }
        assertTrue("ASR canceled before Main cannot publish", lines().isEmpty())
        start(); emit(phrase)
        assertEquals("unpublished text cannot poison a future run", listOf(phrase), lines())
    }
    @Test fun failedMtKeepsSourceAndRetriesOnlyForANewUtterance() {
        create("es", "en"); val phrase = "La linterna azul protege nuestro puerto"
        prepare(phrase)
        await("first actual SDK translation is pending") { MlKitTaskBoundary.translators.firstOrNull()?.tasks?.size == 1 }
        val client = MlKitTaskBoundary.translators.single()
        assertEquals("source is visible before MT", listOf(phrase), lines())
        client.tasks.single().setException(IllegalStateException("controlled MT failure"))
        await("failed MT keeps FIFO loop running") { waiting.entered.count == 0L }
        emit(phrase, 1499)
        assertEquals("failure does not create an every-window retry", 1, client.tasks.size)
        advance(1); prepare(phrase)
        await("same phrase in new utterance retries MT") { client.tasks.size == 2 || waiting.entered.count == 0L }
        assertEquals("new utterance gets a fresh SDK task", 2, client.tasks.size)
        assertEquals(listOf(phrase, phrase), lines())
        client.tasks.last().setResult("The blue lantern protects our harbor")
        await("late success replaces only its ASR line") { waiting.entered.count == 0L }
        assertEquals(listOf(phrase, "The blue lantern protects our harbor"), lines())
    }
    @Test fun growingUtteranceRevisesAndOnlyEightyLinesAreRetained() {
        create(); emit("The silver lantern guards"); emit("The silver lantern guards the harbor", 1)
        assertEquals(listOf("The silver lantern guards the harbor"), lines())
        repeat(82) { emit("Distinct harbor sentence number $it", 1500) }
        assertEquals(80, lines().size)
        assertEquals("Distinct harbor sentence number 2", lines().first())
        assertEquals("Distinct harbor sentence number 81", lines().last())
        assertEquals(lines().joinToString("\n\n"), activity.findViewById<TextView>(R.id.captionText).text.toString())
    }
}
