package com.hatsunama.captionaction.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
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
import org.robolectric.shadows.ShadowSettings

/** Actual permission UI; these tests do not grant permissions on a real device. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [29, 33], application = CaptionActionApp::class)
class PermissionStepRecoveryTest {
    private lateinit var controller: ActivityController<PermissionStepActivity>
    private val activity get() = controller.get()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun title() = activity.findViewById<TextView>(R.id.permTitle).text.toString()
    private fun launch() {
        controller = Robolectric.buildActivity(PermissionStepActivity::class.java).create().start().resume()
    }
    private fun resume() { controller.pause().resume() }

    @Before fun setup() {
        ShadowSettings.setCanDrawOverlays(false)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        // Keep optional notification state out of the required-grant scenarios.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
    }

    @Test fun missingOverlayIsRequestedBeforeAudio() {
        launch()
        assertEquals(activity.getString(R.string.permission_overlay_title), title())
        activity.findViewById<View>(R.id.btnPermPrimary).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
        assertEquals("package:${activity.packageName}", intent.data.toString())
        resume() // Returning without granting must not advance or request anything again.
        assertEquals(activity.getString(R.string.permission_overlay_title), title())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test fun overlaySettingsReturnAdvancesOnlyToMissingAudio() {
        launch()
        ShadowSettings.setCanDrawOverlays(true)
        resume()
        assertEquals(activity.getString(R.string.permission_audio_title), title())
        assertNull(shadowOf(activity).lastRequestedPermission)
        activity.findViewById<View>(R.id.btnPermPrimary).performClick()
        assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), shadowOf(activity).lastRequestedPermission.requestedPermissions)
    }

    @Test fun deniedAudioHasExplicitSettingsRecoveryWithoutAnotherPermissionPrompt() {
        ShadowSettings.setCanDrawOverlays(true)
        launch()
        val settings = activity.findViewById<View>(R.id.btnPermSecondary)
        assertEquals("Recovery must remain available even if Android stops showing permission dialogs", View.VISIBLE, settings.visibility)
        settings.performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${activity.packageName}", intent.data.toString())
        assertNull(shadowOf(activity).lastRequestedPermission)
        resume() // Denied or cancelled in Settings: stay on Audio, never loop.
        assertEquals(activity.getString(R.string.permission_audio_title), title())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun deniedAudioRequestKeepsRecoveryAvailableAndDoesNotFinish() {
        ShadowSettings.setCanDrawOverlays(true)
        launch()
        activity.findViewById<View>(R.id.btnPermPrimary).performClick()
        val request = shadowOf(activity).lastRequestedPermission
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(activity).nextStartedActivity.action)
        activity.onRequestPermissionsResult(
            request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        assertEquals(activity.getString(R.string.permission_audio_title), title())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnPermSecondary).visibility)
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun audioSettingsReturnRechecksGrantWithoutRequestingProjection() {
        ShadowSettings.setCanDrawOverlays(true)
        launch()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        resume()
        assertEquals(activity.getString(R.string.permission_projection_title), title())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertFalse(activity.isFinishing)
    }

    @Test fun revokedOverlayReturnsToRequiredStepBeforeCompletion() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        launch()
        assertEquals(activity.getString(R.string.permission_projection_title), title())
        ShadowSettings.setCanDrawOverlays(false)
        resume()
        assertEquals(activity.getString(R.string.permission_overlay_title), title())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun audioRecoverySurvivesRecreationWithoutRequestingAgain() {
        ShadowSettings.setCanDrawOverlays(true)
        launch()
        controller.recreate()
        assertEquals(activity.getString(R.string.permission_audio_title), title())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnPermSecondary).visibility)
        assertNull(shadowOf(activity).lastRequestedPermission)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        resume()
        assertEquals(activity.getString(R.string.permission_projection_title), title())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun completionRechecksRevokedAudioEvenWithoutLifecycleCallback() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        launch()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        activity.findViewById<View>(R.id.btnPermPrimary).performClick()
        assertEquals(activity.getString(R.string.permission_audio_title), title())
        assertFalse(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun completionRechecksRevokedOverlayEvenWithoutLifecycleCallback() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        launch()
        ShadowSettings.setCanDrawOverlays(false)
        activity.findViewById<View>(R.id.btnPermPrimary).performClick()
        assertEquals(activity.getString(R.string.permission_overlay_title), title())
        assertFalse(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test @Config(sdk = [33]) fun notificationDenialRemainsOptionalAndDoesNotLoopOnResume() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        launch()
        assertEquals(activity.getString(R.string.permission_notif_title), title())
        activity.findViewById<View>(R.id.btnPermPrimary).performClick()
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(activity).nextStartedActivity.action)
        activity.onRequestPermissionsResult(
            request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED)
        )
        assertEquals(activity.getString(R.string.permission_projection_title), title())
        resume()
        assertEquals(activity.getString(R.string.permission_projection_title), title())
        assertSame(request, shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test @Config(sdk = [33]) fun optionalNotificationsCanBeSkippedWithoutPrompt() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        launch()
        activity.findViewById<View>(R.id.btnPermSecondary).performClick()
        assertEquals(activity.getString(R.string.permission_projection_title), title())
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun cancellationNeverRequestsCaptureOrCompletesSetup() {
        launch()
        activity.findViewById<View>(R.id.btnPermCancel).performClick()
        assertTrue(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
