package com.hatsunama.captionaction.ui.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import org.robolectric.shadows.ShadowDialog
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
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
@Config(sdk = [29, 33], application = CaptionActionApp::class,
    shadows = [MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class, MlKitTaskBoundary.ModelManager::class],
    instrumentedPackages = ["com.google.mlkit.nl.translate.Translation", "com.google.mlkit.nl.languageid.LanguageIdentification", "com.google.mlkit.common.model.RemoteModelManager"])
class HomeMicrophonePermissionRecoveryTest {
    private lateinit var controller: ActivityController<HomeActivity>
    private val app get() = RuntimeEnvironment.getApplication() as CaptionActionApp
    private val home get() = controller.get()

    @Before fun setup() {
        MlKitTaskBoundary.reset()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        MlKitTaskBoundary.downloadedTask = Tasks.forResult(MlKitTranslationEngine.COMMON_SOURCES.map { TranslateRemoteModel.Builder(it).build() }.toSet())
        runBlocking { app.settings.update { it.copy(inputLanguage = "en", targetLanguage = "en") } }
        controller = Robolectric.buildActivity(HomeActivity::class.java).create().start().resume()
        until("initial settings") { home.findViewById<TextView>(R.id.statusText).text.contains("ASR-only") }
    }

    @After fun cleanup() {
        if (::controller.isInitialized && !home.isDestroyed) controller.pause().stop().destroy()
        MlKitTaskBoundary.unblock()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun until(message: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.yield() }
        assertTrue(message, condition())
    }

    private fun tryOpenMic() {
        HomeActivity::class.java.getDeclaredMethod("openMicTranslatorAfterAudioPerm").apply {
            isAccessible = true
        }.invoke(home)
    }

    private fun denyRequest(): AlertDialog {
        tryOpenMic()
        val request = shadowOf(home).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(home).nextStartedActivity.action)
        // Use the framework dispatcher so its in-flight request flag is cleared too.
        val result = Intent()
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", request.requestedPermissions)
            .putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", intArrayOf(PackageManager.PERMISSION_DENIED))
        Activity::class.java.getDeclaredMethod("dispatchRequestPermissionsResult", Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }.invoke(home, request.requestCode, result)
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("Denied microphone permission must offer actionable recovery", dialog is AlertDialog && dialog.isShowing)
        return dialog as AlertDialog
    }

    @Test fun denialOffersExplicitSettingsWithoutStartingMicrophone() {
        val dialog = denyRequest()
        assertNull(shadowOf(home).nextStartedActivity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val settings = shadowOf(home).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${home.packageName}", settings.data.toString())
        assertNull(shadowOf(home).nextStartedActivity)
    }

    @Test fun cancellingRecoveryDoesNotStartMicrophoneAndRetryRemainsPossible() {
        denyRequest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(home).nextStartedActivity)
        assertTrue(denyRequest().isShowing)
    }

    @Test fun destroyingHomeDismissesRecoveryWithoutStartingMicrophone() {
        val dialog = denyRequest()
        controller.pause().stop().destroy()
        assertFalse(dialog.isShowing)
        assertNull(shadowOf(home).nextStartedActivity)
    }

    @Test fun grantInSettingsRequiresExplicitRetryBeforeOpeningTranslator() {
        denyRequest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(home).nextStartedActivity.action)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller.pause().resume()
        assertNull("Returning from Settings must not start recording", shadowOf(home).nextStartedActivity)
        tryOpenMic()
        assertEquals(MicTranslatorActivity::class.java.name, shadowOf(home).nextStartedActivity.component!!.className)
        assertNull(shadowOf(home).nextStartedActivity)
    }
}
