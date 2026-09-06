package com.hatsunama.captionaction.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.ui.live.LiveSessionActivity
import com.hatsunama.captionaction.ui.models.ModelManagerActivity
import com.hatsunama.captionaction.ui.overlay.OverlayPreviewActivity
import com.hatsunama.captionaction.ui.settings.LanguageSettingsActivity
import com.hatsunama.captionaction.ui.setup.SetupWizardActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<MaterialButton>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnLanguages).setOnClickListener {
            startActivity(Intent(this, LanguageSettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnModels).setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnPreview).setOnClickListener {
            startActivity(Intent(this, OverlayPreviewActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnStartLive).setOnClickListener {
            startActivity(Intent(this, LiveSessionActivity::class.java))
        }

        val app = application as CaptionActionApp
        val status = findViewById<TextView>(R.id.statusText)
        lifecycleScope.launch {
            app.settings.settingsFlow.collectLatest { s ->
                val tier = ModelTier.fromId(s.modelTierId)
                val present = ModelCache(this@HomeActivity).isPresent(tier)
                val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this@HomeActivity)
                } else true
                status.text = buildString {
                    append(if (s.setupComplete) "Setup complete. " else "First-run setup recommended. ")
                    append("Model: ${tier.displayName}")
                    append(if (present) " (on device). " else " (not downloaded). ")
                    append(if (overlayOk) "Overlay OK." else "Overlay permission needed.")
                }
                if (!s.setupComplete) {
                    // Soft-nudge: open wizard once
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as CaptionActionApp
        lifecycleScope.launch {
            val s = app.settings.current()
            if (!s.setupComplete) {
                // leave user on home; wizard is one tap away
            }
        }
    }

    companion object {
        fun openOverlaySettings(activity: AppCompatActivity) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }
        }
    }
}
