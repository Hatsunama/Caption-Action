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
