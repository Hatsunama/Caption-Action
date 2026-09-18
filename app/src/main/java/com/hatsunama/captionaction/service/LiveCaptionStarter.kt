package com.hatsunama.captionaction.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.ui.permissions.PermissionStepActivity

/**
 * Shared start path for Home:
 * Q+ → MediaProjection prompt (playback capture is the intended source);
 * otherwise (or on decline) → mic-only CaptionOverlayService as emergency fallback.
 * PermissionStepActivity never starts the foreground service.
 */
object LiveCaptionStarter {

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

    fun permissionMode(settings: AppSettings): String =
        if (!settings.permissionsWalkthroughComplete) {
            PermissionStepActivity.MODE_SETUP
        } else {
            PermissionStepActivity.MODE_MISSING
        }

    fun needsPermissionWalkthrough(settings: AppSettings, context: Context): Boolean =
        !settings.permissionsWalkthroughComplete || missingRequiredGrants(context)

    fun permissionStepIntent(context: Context, mode: String): Intent =
        Intent(context, PermissionStepActivity::class.java).putExtra(
            PermissionStepActivity.EXTRA_MODE,
            mode
        )

    /** Playback is always preferred; show screen-capture prompt on Q+. */
    fun shouldRequestProjection(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @Deprecated("Playback is always preferred; use shouldRequestProjection()", ReplaceWith("shouldRequestProjection()"))
    fun shouldRequestProjection(preferPlaybackCapture: Boolean): Boolean =
        preferPlaybackCapture && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun createScreenCaptureIntent(context: Context): Intent {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }

    fun startMicAndMinimize(activity: Activity) {
        CaptionOverlayService.start(activity)
        activity.moveTaskToBack(true)
    }

    fun startWithProjectionAndMinimize(activity: Activity, resultCode: Int, data: Intent) {
        CaptionOverlayService.start(activity, resultCode, data)
        activity.moveTaskToBack(true)
    }

    fun startMicFallbackAfterDecline(activity: Activity) {
        CaptionOverlayService.start(activity)
        Toast.makeText(
            activity,
            activity.getString(R.string.permission_projection_declined_mic),
            Toast.LENGTH_LONG
        ).show()
        activity.moveTaskToBack(true)
    }
}
