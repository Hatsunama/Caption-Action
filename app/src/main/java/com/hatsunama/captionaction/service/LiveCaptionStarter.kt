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
import androidx.core.content.ContextCompat
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.ui.permissions.PermissionStepActivity

object LiveCaptionStarter {

    /**
     * Suppress HomeActivity.onResume auto-stop during Start → projection → launcher handoff.
     * Windowed so a brief resume after the projection result cannot kill the new session.
     */
    @Volatile
    private var handoffUntilElapsed: Long = 0L

    fun beginStartHandoff() {
        handoffUntilElapsed = SystemClock.elapsedRealtime() + 8_000L
    }

    fun endStartHandoff() {
        handoffUntilElapsed = 0L
    }

    fun shouldSuppressHomeAutoStop(): Boolean =
        SystemClock.elapsedRealtime() < handoffUntilElapsed

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
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

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

    fun startMicAndMinimize(activity: Activity) {
        beginStartHandoff()
        CaptionOverlayService.start(activity)
        goToLauncherHome(activity)
    }

    fun startWithProjectionAndMinimize(activity: Activity, resultCode: Int, data: Intent) {
        beginStartHandoff()
        CaptionOverlayService.start(activity, resultCode, data)
        goToLauncherHome(activity)
    }

    fun startMicFallbackAfterDecline(activity: Activity) {
        beginStartHandoff()
        CaptionOverlayService.start(activity)
        Toast.makeText(
            activity,
            activity.getString(R.string.permission_projection_declined_mic),
            Toast.LENGTH_LONG
        ).show()
        goToLauncherHome(activity)
    }
}
