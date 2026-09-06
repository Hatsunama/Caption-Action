package com.hatsunama.captionaction.ui.home

import android.content.Intent
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Mark setup complete so nothing treats first-run as a gate.
        lifecycleScope.launch {
            (application as CaptionActionApp).settings.update { it.copy(setupComplete = true) }
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
                    append("Model: ${tier.displayName}")
                    append(if (present) " ✓ on device. " else " · not downloaded (demo OK). ")
                    append(if (overlayOk) "Overlay ready." else "Overlay permission still needed.")
                    append(" Dual: ").append(if (s.dualSubtitles) "on" else "off")
                    append(" · Target: ").append(s.targetLanguage)
                }
            }
        }
    }
}
