package com.hatsunama.captionaction.ui.home

import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.tasks.Tasks
import com.google.android.material.button.MaterialButton
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
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

/** Actual Home widgets and handlers; SDK Tasks are fixtures, not live capture/ASR proof. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [28], application = CaptionActionApp::class,
    shadows = [MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class, MlKitTaskBoundary.ModelManager::class],
    instrumentedPackages = ["com.google.mlkit.nl.translate.Translation", "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"])
class HomeStartSurfaceTest {
    private lateinit var controller: ActivityController<HomeActivity>
    private val app get() = RuntimeEnvironment.getApplication() as CaptionActionApp
    private val home get() = controller.get()

    @Before fun setup() {
        MlKitTaskBoundary.reset()
        MlKitTaskBoundary.downloadedTask = Tasks.forResult(MlKitTranslationEngine.COMMON_SOURCES.map { TranslateRemoteModel.Builder(it).build() }.toSet())
        runBlocking { app.settings.update { it.copy(inputLanguage = "en", targetLanguage = "en") } }
        controller = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        until("initial settings") { home.findViewById<TextView>(R.id.statusText).text.contains("ASR-only") }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        MlKitTaskBoundary.unblock()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun until(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(message, condition())
    }

    private fun gate(): AlertDialog = HomeActivity::class.java.getDeclaredField("modelGateDialog").apply { isAccessible = true }.get(home) as AlertDialog

    @Test fun formerSampleIsAnAccessibleStartAction() {
        val action = home.findViewById<View>(R.id.previewSample)
        assertTrue("The reported surface must be a button, not a passive sample", action is MaterialButton)
        assertTrue(action.isClickable)
        assertTrue(action.isFocusable)
        assertEquals("Start live captions", (action as TextView).text.toString())
        assertEquals("Start live captions", home.findViewById<TextView>(R.id.btnStartLive).text.toString())
        assertTrue(action.performClick())
        assertTrue("Click must enter the real guarded model flow", gate().isShowing)
        assertNotNull(gate().findViewById<View>(R.id.btnUseAndStart))
    }

    @Test fun bothEntriesCancelAndRecoverThroughTheSameGate() {
        for (id in listOf(R.id.previewSample, R.id.btnStartLive)) {
            assertTrue(home.findViewById<View>(id).performClick())
            val first = gate()
            assertTrue(first.isShowing)
            first.findViewById<View>(R.id.btnCancelGate)!!.performClick()
            assertFalse(first.isShowing)
            assertTrue(home.findViewById<View>(id).performClick())
            assertNotSame(first, gate())
            assertTrue(gate().isShowing)
            assertNull("Opening/cancelling the model gate must not request capture", shadowOf(home).nextStartedActivity)
            gate().findViewById<View>(R.id.btnCancelGate)!!.performClick()
        }
    }

    @Test fun startActionStillShowsTheSelectedCaptionFont() {
        val spinner = home.findViewById<Spinner>(R.id.fontSpinner)
        val action = home.findViewById<TextView>(R.id.previewSample)
        spinner.onItemSelectedListener!!.onItemSelected(spinner, null, 2, 2L)
        assertEquals(Typeface.MONOSPACE, action.typeface)
        spinner.onItemSelectedListener!!.onItemSelected(spinner, null, 1, 1L)
        assertEquals(Typeface.SANS_SERIF, action.typeface)
        assertEquals("Start live captions", action.text.toString())
        assertTrue(action.performClick())
        assertTrue(gate().isShowing)
    }
}
