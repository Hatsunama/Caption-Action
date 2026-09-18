package com.hatsunama.captionaction.ui.models

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.service.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagerActivity : AppCompatActivity() {
    private lateinit var cache: ModelCache
    private var downloadJob: Job? = null
    private var activeDownloader: ModelDownloadManager? = null
    private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)
        cache = ModelCache(this)
        val app = application as CaptionActionApp
        val group = findViewById<RadioGroup>(R.id.modelGroup)
        val status = findViewById<TextView>(R.id.modelStatus)
        val loading = findViewById<FrameLayout>(R.id.loadingOverlay)
        val progress = findViewById<ProgressBar>(R.id.downloadProgress)
        val percent = findViewById<TextView>(R.id.downloadPercent)
        val btnDownload = findViewById<MaterialButton>(R.id.btnDownload)
        val btnSelect = findViewById<MaterialButton>(R.id.btnSelect)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancelDownload)

        lifecycleScope.launch {
            val s = app.settings.current()
            when (ModelTier.fromId(s.modelTierId)) {
                ModelTier.FAST -> findViewById<RadioButton>(R.id.radioFast).isChecked = true
                ModelTier.BALANCED -> findViewById<RadioButton>(R.id.radioBalanced).isChecked = true
                ModelTier.ACCURATE -> findViewById<RadioButton>(R.id.radioAccurate).isChecked = true
            }
            refreshStatus(status)
        }

        group.setOnCheckedChangeListener { _, _ -> refreshStatus(status) }

        btnSelect.setOnClickListener {
            val tier = selectedTier()
            lifecycleScope.launch {
                app.settings.update { it.copy(modelTierId = tier.id) }
                Toast.makeText(this@ModelManagerActivity, "Using ${tier.displayName}", Toast.LENGTH_SHORT).show()
                refreshStatus(status)
            }
        }

        btnCancel.setOnClickListener {
            activeDownloader?.cancel()
        }

        btnDownload.setOnClickListener {
            if (downloading || !btnDownload.isEnabled) return@setOnClickListener
            val tier = selectedTier()
            if (cache.isReadyForAsr(tier) || cache.isPresent(tier)) {
                Toast.makeText(this, getString(R.string.download_already_on_device), Toast.LENGTH_SHORT).show()
                refreshStatus(status)
                return@setOnClickListener
            }
            val already = cache.partialBytes(tier)
            val need = (tier.approxBytes - already).coerceAtLeast(0L)
            if (cache.freeBytes() < need + 5L * 1024 * 1024) {
                Toast.makeText(this, getString(R.string.error_storage), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            loading.visibility = View.VISIBLE
            val startPct = if (tier.approxBytes > 0 && already > 0) {
                ((already * 100) / tier.approxBytes).toInt().coerceIn(0, 99)
            } else 0
            progress.progress = startPct
            percent.text = "$startPct%"
            downloading = true
            btnDownload.isEnabled = false
            btnDownload.alpha = 0.45f
            val mgr = ModelDownloadManager(cache)
            activeDownloader = mgr
            downloadJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    mgr.download(tier) { p ->
                        runOnUiThread {
                            progress.progress = p.percent
                            percent.text = "${p.percent}%"
                        }
                    }
                }
                loading.visibility = View.GONE
                downloading = false
                activeDownloader = null
                when {
                    result.isSuccess -> {
                        app.settings.update { it.copy(modelTierId = tier.id) }
                        Toast.makeText(this@ModelManagerActivity, getString(R.string.model_ready), Toast.LENGTH_SHORT).show()
                    }
                    result.exceptionOrNull() is ModelDownloadManager.CancelledException -> {
                        Toast.makeText(
                            this@ModelManagerActivity,
                            getString(R.string.download_can_resume),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        val msg = result.exceptionOrNull()?.message ?: getString(R.string.error_model)
                        Toast.makeText(this@ModelManagerActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                refreshStatus(status)
            }
        }
    }

    override fun onDestroy() {
        activeDownloader?.cancel()
        downloadJob?.cancel()
        super.onDestroy()
    }

    private fun selectedTier(): ModelTier = when (findViewById<RadioGroup>(R.id.modelGroup).checkedRadioButtonId) {
        R.id.radioFast -> ModelTier.FAST
        R.id.radioAccurate -> ModelTier.ACCURATE
        else -> ModelTier.BALANCED
    }

    private fun refreshStatus(status: TextView) {
        val tier = selectedTier()
        val ready = cache.isReadyForAsr(tier)
        val present = cache.isPresent(tier)
        val partial = cache.isPartial(tier)
        status.text = buildString {
            append(tier.displayName)
            append(": ")
            when {
                ready -> append(getString(R.string.model_ready))
                present -> append(getString(R.string.model_ggml_not_asr))
                partial -> {
                    append(getString(R.string.model_partial))
                    append(" (")
                    append(cache.partialBytes(tier) / (1024 * 1024))
                    append(" MB) — ")
                    append(getString(R.string.download_can_resume))
                }
                else -> append(getString(R.string.model_missing))
            }
            append("\n")
            append("Fast = SenseVoice · Balanced/Accurate = whisper.cpp. Cancel keeps a partial for resume.")
        }
        val btn = findViewById<MaterialButton>(R.id.btnDownload)
        val onDevice = ready || present
        when {
            downloading -> {
                btn.isEnabled = false
                btn.alpha = 0.45f
            }
            onDevice -> {
                btn.isEnabled = false
                btn.alpha = 0.45f
                btn.text = getString(R.string.download_already_on_device)
            }
            partial -> {
                btn.isEnabled = true
                btn.alpha = 1f
                btn.text = getString(R.string.resume_download)
            }
            else -> {
                btn.isEnabled = true
                btn.alpha = 1f
                btn.text = getString(R.string.download_model)
            }
        }
    }
}
