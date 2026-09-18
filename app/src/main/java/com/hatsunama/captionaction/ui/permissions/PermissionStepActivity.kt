package com.hatsunama.captionaction.ui.permissions

import android.Manifest
import android.content.Intent
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
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.service.LiveCaptionStarter
import kotlinx.coroutines.launch

class PermissionStepActivity : AppCompatActivity() {

    private enum class Step { OVERLAY, AUDIO, NOTIFICATIONS, PROJECTION }

    private var step = Step.OVERLAY

    private val audioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) advanceAfterAudio()
        else Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        goTo(Step.PROJECTION)
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        finishWalkthrough()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_step)
        findViewById<MaterialButton>(R.id.btnPermCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnPermPrimary).setOnClickListener { onPrimary() }
        findViewById<MaterialButton>(R.id.btnPermSecondary).setOnClickListener { onSecondary() }
        resolveInitialStep()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (step == Step.OVERLAY && LiveCaptionStarter.canDrawOverlays(this)) {
            advanceAfterOverlay()
        }
    }

    private fun resolveInitialStep() {
        step = when {
            !LiveCaptionStarter.canDrawOverlays(this) -> Step.OVERLAY
            !LiveCaptionStarter.hasAudioPermission(this) -> Step.AUDIO
            LiveCaptionStarter.needsNotificationPermission(this) -> Step.NOTIFICATIONS
            else -> Step.PROJECTION
        }
    }

    private fun render() {
        val label = findViewById<TextView>(R.id.permStepLabel)
        val title = findViewById<TextView>(R.id.permTitle)
        val body = findViewById<TextView>(R.id.permBody)
        val primary = findViewById<MaterialButton>(R.id.btnPermPrimary)
        val secondary = findViewById<MaterialButton>(R.id.btnPermSecondary)
        val hint = findViewById<TextView>(R.id.permHint)

        when (step) {
            Step.OVERLAY -> {
                label.text = "Step 1 · Overlay"
                title.setText(R.string.permission_overlay_title)
                body.setText(R.string.permission_overlay_why)
                primary.setText(R.string.permission_overlay_action)
                secondary.visibility = View.GONE
                hint.text = "One permission at a time — no popup pile-ups."
            }
            Step.AUDIO -> {
                label.text = "Step 2 · Device audio"
                title.setText(R.string.permission_audio_title)
                body.setText(R.string.permission_audio_why)
                primary.setText(R.string.permission_audio_action)
                secondary.visibility = View.GONE
                hint.text = "Android requires this for playback capture — Caption Action never uses the microphone."
            }
            Step.NOTIFICATIONS -> {
                label.text = "Step 3 · Notifications"
                title.setText(R.string.permission_notif_title)
                body.setText(R.string.permission_notif_why)
                primary.setText(R.string.permission_notif_action)
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.continue_label) + " without"
                hint.text = "Optional but keeps the session stable."
            }
            Step.PROJECTION -> {
                label.text = if (Build.VERSION.SDK_INT >= 33) "Step 4 · Screen audio" else "Step 3 · Screen audio"
                title.setText(R.string.permission_projection_title)
                if (LiveCaptionStarter.shouldRequestProjection()) {
                    body.setText(R.string.permission_projection_why)
                    primary.setText(R.string.permission_projection_action)
                    hint.text = "Required for device-audio captions. Declining means Start cannot caption — microphone is never used."
                } else {
                    body.setText(R.string.error_requires_android_10)
                    primary.setText(R.string.continue_label)
                    hint.text = "This device cannot capture app playback audio."
                }
                secondary.visibility = View.GONE
            }
        }
    }

    private fun onPrimary() {
        when (step) {
            Step.OVERLAY -> {
                if (LiveCaptionStarter.canDrawOverlays(this)) {
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
            Step.AUDIO -> {
                if (LiveCaptionStarter.hasAudioPermission(this)) advanceAfterAudio()
                else audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            Step.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    goTo(Step.PROJECTION)
                }
            }
            Step.PROJECTION -> requestProjectionConsent()
        }
    }

    private fun onSecondary() {
        when (step) {
            Step.NOTIFICATIONS -> goTo(Step.PROJECTION)
            else -> {}
        }
    }

    private fun advanceAfterOverlay() {
        when {
            !LiveCaptionStarter.hasAudioPermission(this) -> goTo(Step.AUDIO)
            LiveCaptionStarter.needsNotificationPermission(this) -> goTo(Step.NOTIFICATIONS)
            else -> goTo(Step.PROJECTION)
        }
    }

    private fun advanceAfterAudio() {
        if (LiveCaptionStarter.needsNotificationPermission(this)) {
            goTo(Step.NOTIFICATIONS)
        } else {
            goTo(Step.PROJECTION)
        }
    }

    private fun goTo(next: Step) {
        step = next
        render()
    }

    private fun requestProjectionConsent() {
        if (LiveCaptionStarter.shouldRequestProjection()) {
            projectionLauncher.launch(LiveCaptionStarter.createScreenCaptureIntent(this))
        } else {
            Toast.makeText(this, getString(R.string.error_requires_android_10), Toast.LENGTH_LONG).show()
            finishWalkthrough()
        }
    }

    private fun finishWalkthrough() {
        lifecycleScope.launch {
            (application as CaptionActionApp).settings.update {
                it.copy(permissionsWalkthroughComplete = true)
            }
            setResult(RESULT_OK)
            finish()
        }
    }

}
