package com.hatsunama.captionaction.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.ui.permissions.PermissionStepActivity

object LiveCaptionStarter {

    /**
     * Suppress HomeActivity.onResume auto-stop during Start → projection → launcher handoff.
     * Windowed (see [StartHandoffGate.DEFAULT_WINDOW_MS]) so Seeker resume after grant/minimize
     * cannot kill the new session.
     */
    private val handoffGate = StartHandoffGate(clock = { SystemClock.elapsedRealtime() })

    fun beginStartHandoff() {
        handoffGate.begin()
    }

    fun endStartHandoff() {
        handoffGate.end()
        handoffGate.clearServiceStarted()
    }

    fun markSessionStarted() {
        handoffGate.markServiceStarted()
    }

    fun shouldSuppressHomeAutoStop(): Boolean =
        handoffGate.shouldSuppressHomeAutoStop()

    fun shouldAutoStopOnHomeResume(serviceRunning: Boolean): Boolean =
        handoffGate.shouldAutoStopOnHomeResume(serviceRunning)

    fun missingRequiredGrants(context: Context): Boolean {
        if (!canDrawOverlays(context)) return true
        if (!hasAudioPermission(context)) return true
        return false
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun needsPermissionWalkthrough(settings: AppSettings, context: Context): Boolean =
        !settings.permissionsWalkthroughComplete || missingRequiredGrants(context)

    fun permissionStepIntent(context: Context): Intent =
        Intent(context, PermissionStepActivity::class.java)

    fun shouldRequestProjection(): Boolean =
        ProjectionFreshStart.shouldLaunchSystemProjectionPrompt(
            apiAtLeastQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            // Never pass a remembered grant — always re-prompt.
            hasCachedPriorGrant = false
        )

    /**
     * Live overlay + RECORD_AUDIO against phone state — call BEFORE
     * [createScreenCaptureIntent] so the share-one-app / entire-screen UI is
     * not shown when capture would still fail after Allow.
     */
    fun liveCapturePrechecksOk(context: Context): Boolean =
        ProjectionFreshStart.precheckAllowsShareUi(
            overlayGranted = canDrawOverlays(context),
            recordAudioGranted = hasAudioPermission(context)
        )

    /**
     * Always a fresh [MediaProjectionManager.createScreenCaptureIntent] —
     * never reuse a prior RESULT_OK data Intent across Starts.
     */
    fun createScreenCaptureIntent(context: Context): Intent {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    /** Prefer launcher Home — Seeker may ignore moveTaskToBack alone. */
    fun goToLauncherHome(activity: Activity) {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(home)
        } catch (_: Exception) {
            activity.moveTaskToBack(true)
        }
        // Keep handoff window; do not clear here — Home may still resume briefly on Seeker.
    }

    fun startWithProjectionAndMinimize(activity: Activity, resultCode: Int, data: Intent) {
        // Grant path: extend handoff + mark session so onResume cannot race-kill.
        handoffGate.extend()
        handoffGate.markServiceStarted()
        CaptionOverlayService.start(activity, resultCode, data)
        goToLauncherHome(activity)
    }

    /**
     * Projection declined — stay on Home (main menu), explain screen share is required.
     * Never starts the overlay service or microphone.
     */
    fun failProjectionDeclined(activity: Activity) {
        endStartHandoff()
        showCaptureRequiredDialog(
            activity,
            activity.getString(R.string.error_projection_required)
        )
    }

    /** Pre-Q: AudioPlaybackCapture unavailable — fail honestly (no mic). */
    fun failRequiresAndroid10(activity: Activity) {
        endStartHandoff()
        showCaptureRequiredDialog(
            activity,
            activity.getString(R.string.error_requires_android_10)
        )
    }

    private fun showCaptureRequiredDialog(activity: Activity, message: String) {
        if (activity.isFinishing) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.permission_projection_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setCancelable(true)
                .show()
        } catch (_: Exception) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }
}
