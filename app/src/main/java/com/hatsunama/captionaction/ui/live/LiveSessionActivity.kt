package com.hatsunama.captionaction.ui.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.service.CaptionOverlayService
import kotlinx.coroutines.launch

class LiveSessionActivity : AppCompatActivity() {
    private var running = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) maybeStartProjection()
        else Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            CaptionOverlayService.start(this, result.resultCode, result.data)
            markRunning(true)
        } else {
            // Fallback to mic-only
            CaptionOverlayService.start(this)
            markRunning(true)
            Toast.makeText(this, "Using microphone fallback", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_session)

        findViewById<MaterialButton>(R.id.btnToggle).setOnClickListener {
            if (running) {
                CaptionOverlayService.stop(this)
                markRunning(false)
            } else {
                ensureOverlayThenStart()
            }
        }

        lifecycleScope.launch {
            CaptionOverlayService.events.collect { event ->
                val status = findViewById<TextView>(R.id.liveStatus)
                val caption = findViewById<TextView>(R.id.lastCaption)
                when (event) {
                    is CaptionOverlayService.SessionEvent.Started -> {
                        status.text = "Running via ${event.source} · engine: ${event.engine}"
                        markRunning(true)
                    }
                    is CaptionOverlayService.SessionEvent.Caption -> {
                        caption.text = buildString {
                            append(event.primary)
                            if (!event.secondary.isNullOrBlank()) {
                                append("\n")
                                append(event.secondary)
                            }
                        }
                    }
                    is CaptionOverlayService.SessionEvent.Error -> {
                        status.text = event.message
                        markRunning(false)
                    }
                    CaptionOverlayService.SessionEvent.Stopped -> {
                        status.text = "Stopped"
                        markRunning(false)
                    }
                }
            }
        }
    }

    private fun ensureOverlayThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        maybeStartProjection()
    }

    private fun maybeStartProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            CaptionOverlayService.start(this)
            markRunning(true)
        }
    }

    private fun markRunning(value: Boolean) {
        running = value
        findViewById<MaterialButton>(R.id.btnToggle).text =
            if (value) getString(R.string.stop_live) else getString(R.string.start_live)
    }
}
