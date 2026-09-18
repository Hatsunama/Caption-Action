package com.hatsunama.captionaction.ui.live

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.service.LiveCaptionStarter
import kotlinx.coroutines.launch

class LiveSessionActivity : AppCompatActivity() {
    private var running = false

    private val permissionFlow = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // PermissionStep never starts the FGS — user must tap Start again.
        markRunning(CaptionOverlayService.instance != null)
        if (it.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.permission_setup_done), Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            LiveCaptionStarter.startWithProjectionAndMinimize(this, result.resultCode, result.data!!)
            markRunning(true)
        } else {
            LiveCaptionStarter.startMicFallbackAfterDecline(this)
            markRunning(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_session)

        findViewById<MaterialButton>(R.id.btnToggle).setOnClickListener {
            if (running || CaptionOverlayService.instance != null) {
                CaptionOverlayService.stop(this)
                markRunning(false)
            } else {
                lifecycleScope.launch { onStartCaptions() }
            }
        }

        lifecycleScope.launch {
            CaptionOverlayService.events.collect { event ->
                val status = findViewById<TextView>(R.id.liveStatus)
                val caption = findViewById<TextView>(R.id.lastCaption)
                when (event) {
                    is CaptionOverlayService.SessionEvent.Started -> {
                        status.text = "Running via ${event.source} · ${event.engine}"
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
                        findViewById<TextView>(R.id.lastCaption).text = getString(R.string.listening)
                        markRunning(false)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        markRunning(CaptionOverlayService.instance != null)
    }

    private fun app(): CaptionActionApp = application as CaptionActionApp

    private suspend fun onStartCaptions() {
        val settings = app().settings.current()
        if (LiveCaptionStarter.needsPermissionWalkthrough(settings, this)) {
            permissionFlow.launch(
                LiveCaptionStarter.permissionStepIntent(
                    this,
                    LiveCaptionStarter.permissionMode(settings)
                )
            )
            return
        }
        if (LiveCaptionStarter.shouldRequestProjection()) {
            projectionLauncher.launch(LiveCaptionStarter.createScreenCaptureIntent(this))
        } else {
            LiveCaptionStarter.startMicAndMinimize(this)
            markRunning(true)
        }
    }

    private fun markRunning(value: Boolean) {
        running = value
        findViewById<MaterialButton>(R.id.btnToggle).text =
            if (value) getString(R.string.stop_live) else getString(R.string.start_live)
    }
}
