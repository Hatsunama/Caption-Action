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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import kotlinx.coroutines.launch

/**
 * One-at-a-time permission gate for device playback captions:
 * overlay → device audio → notifications (API 33+) → screen/audio capture.
 *
 * SETUP / MISSING modes never start the overlay service — they return to Home
 * so the user taps Start again. MediaProjection is requested during the walkthrough
 * for education/consent, then discarded; Start will request it again for real.
 */
class PermissionStepActivity : AppCompatActivity() {

    private enum class Step { OVERLAY, AUDIO, NOTIFICATIONS, PROJECTION }

    private var step = Step.OVERLAY
    private var mode = MODE_SETUP

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
        // Walkthrough only — do not start captions. User taps Start again on Home.
        finishWalkthrough()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_step)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SETUP
        findViewById<MaterialButton>(R.id.btnPermCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnPermPrimary).setOnClickListener { onPrimary() }
        findViewById<MaterialButton>(R.id.btnPermSecondary).setOnClickListener { onSecondary() }
        resolveInitialStep()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (step == Step.OVERLAY && canDrawOverlays()) {
            advanceAfterOverlay()
        }
    }

    private fun resolveInitialStep() {
        step = when {
            !canDrawOverlays() -> Step.OVERLAY
            !hasAudio() -> Step.AUDIO
            needsNotif() -> Step.NOTIFICATIONS
            else -> Step.PROJECTION
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun hasAudio(): Boolean =
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
                hint.text = "Required so Android can share sounds playing on the device."
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
                body.setText(R.string.permission_projection_why)
                primary.setText(R.string.permission_projection_action)
                // No "mic only later" — playback capture is the product.
                secondary.visibility = View.GONE
                hint.text = "After this, you return to Home and tap Start again to begin."
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
            Step.AUDIO -> {
                if (hasAudio()) advanceAfterAudio()
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
            !hasAudio() -> goTo(Step.AUDIO)
            needsNotif() -> goTo(Step.NOTIFICATIONS)
            else -> goTo(Step.PROJECTION)
        }
    }

    private fun advanceAfterAudio() {
        if (needsNotif()) goTo(Step.NOTIFICATIONS) else goTo(Step.PROJECTION)
    }

    private fun goTo(next: Step) {
        step = next
        render()
    }

    private fun requestProjectionConsent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            finishWalkthrough()
        }
    }

    private fun finishWalkthrough() {
        lifecycleScope.launch {
            (application as CaptionActionApp).settings.update {
                it.copy(permissionsWalkthroughComplete = true, setupComplete = true)
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SETUP = "setup"
        const val MODE_MISSING = "missing"
    }
}
