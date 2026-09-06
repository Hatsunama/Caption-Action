package com.hatsunama.captionaction.ui.live

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.ui.permissions.PermissionStepActivity
import kotlinx.coroutines.launch

class LiveSessionActivity : AppCompatActivity() {
    private var running = false

    private val permissionFlow = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Service start is handled inside PermissionStepActivity on success.
        markRunning(CaptionOverlayService.instance != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_session)

        findViewById<MaterialButton>(R.id.btnToggle).setOnClickListener {
            if (running || CaptionOverlayService.instance != null) {
                CaptionOverlayService.stop(this)
                markRunning(false)
            } else {
                permissionFlow.launch(Intent(this, PermissionStepActivity::class.java))
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

    private fun markRunning(value: Boolean) {
        running = value
        findViewById<MaterialButton>(R.id.btnToggle).text =
            if (value) getString(R.string.stop_live) else getString(R.string.start_live)
    }
}
