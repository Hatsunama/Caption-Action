package com.hatsunama.captionaction.ui.permissions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.service.CaptionOverlayService

/**
 * One-at-a-time permission gate:
 * overlay → mic → notifications (API 33+) → media projection.
 * Never opens Settings and fires runtime popups in a pile.
 */
class PermissionStepActivity : AppCompatActivity() {

    private enum class Step { OVERLAY, MIC, NOTIFICATIONS, PROJECTION }

    private var step = Step.OVERLAY
    private var preferPlayback = true

    private val micLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) advanceAfterMic()
        else Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notifications are helpful but not hard-required for captions.
        goTo(Step.PROJECTION)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            CaptionOverlayService.start(this, result.resultCode, result.data)
        } else {
            CaptionOverlayService.start(this)
            Toast.makeText(this, "Using microphone fallback", Toast.LENGTH_SHORT).show()
        }
        setResult(RESULT_OK)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_step)
        findViewById<MaterialButton>(R.id.btnPermCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnPermPrimary).setOnClickListener { onPrimary() }
        findViewById<MaterialButton>(R.id.btnPermSecondary).setOnClickListener { onSecondary() }
        lifecycleScope.launch {
            preferPlayback = (application as CaptionActionApp).settings.current().preferPlaybackCapture
        }
        resolveInitialStep()
        render()
    }

    override fun onResume() {
        super.onResume()
        // Returning from overlay Settings — advance only if granted.
        if (step == Step.OVERLAY && canDrawOverlays()) {
            advanceAfterOverlay()
        }
    }

    private fun resolveInitialStep() {
        step = when {
            !canDrawOverlays() -> Step.OVERLAY
            !hasMic() -> Step.MIC
            needsNotif() -> Step.NOTIFICATIONS
            else -> Step.PROJECTION
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun needsNotif(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun render() {
        val label = findViewById<TextView>(R.id.permStepLabel)
        val title = findViewById<TextView>(R.id.permTitle)
        val body = findViewById<TextView>(R.id.permBody)
        val primary = findViewById<MaterialButton>(R.id.btnPermPrimary)
        val secondary = findViewById<MaterialButton>(R.id.btnPermSecondary)

        when (step) {
            Step.OVERLAY -> {
                label.text = "Step 1 · Overlay"
                title.setText(R.string.permission_overlay_title)
                body.setText(R.string.permission_overlay_why)
                primary.setText(R.string.permission_overlay_action)
                secondary.visibility = View.GONE
            }
            Step.MIC -> {
                label.text = "Step 2 · Microphone"
                title.setText(R.string.permission_mic_title)
                body.setText(R.string.permission_mic_why)
                primary.setText(R.string.permission_mic_action)
                secondary.visibility = View.GONE
            }
            Step.NOTIFICATIONS -> {
                label.text = "Step 3 · Notifications"
                title.setText(R.string.permission_notif_title)
                body.setText(R.string.permission_notif_why)
                primary.setText(R.string.permission_notif_action)
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.continue_label) + " without"
            }
            Step.PROJECTION -> {
                label.text = if (Build.VERSION.SDK_INT >= 33) "Step 4 · Screen audio" else "Step 3 · Screen audio"
                title.setText(R.string.permission_projection_title)
                body.setText(R.string.permission_projection_why)
                primary.setText(R.string.permission_projection_action)
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.permission_skip_mic)
            }
        }
    }

    private fun onPrimary() {
        when (step) {
            Step.OVERLAY -> {
                if (canDrawOverlays()) {
                    advanceAfterOverlay()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            Step.MIC -> {
                if (hasMic()) advanceAfterMic()
                else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            Step.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    goTo(Step.PROJECTION)
                }
            }
            Step.PROJECTION -> startProjectionOrMic(preferPlayback = preferPlayback)
        }
    }

    private fun onSecondary() {
        when (step) {
            Step.NOTIFICATIONS -> goTo(Step.PROJECTION)
            Step.PROJECTION -> startProjectionOrMic(preferPlayback = false)
            else -> {}
        }
    }

    private fun advanceAfterOverlay() {
        when {
            !hasMic() -> goTo(Step.MIC)
            needsNotif() -> goTo(Step.NOTIFICATIONS)
            else -> goTo(Step.PROJECTION)
        }
    }

    private fun advanceAfterMic() {
        if (needsNotif()) goTo(Step.NOTIFICATIONS) else goTo(Step.PROJECTION)
    }

    private fun goTo(next: Step) {
        step = next
        render()
    }

    private fun startProjectionOrMic(preferPlayback: Boolean) {
        if (preferPlayback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            CaptionOverlayService.start(this)
            setResult(RESULT_OK)
            finish()
        }
    }
}
