package com.hatsunama.captionaction.ui.models

import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelManagerActivity : AppCompatActivity() {
    private lateinit var cache: ModelCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)
        cache = ModelCache(this)
        val app = application as CaptionActionApp
        val group = findViewById<RadioGroup>(R.id.modelGroup)
        val status = findViewById<TextView>(R.id.modelStatus)
        val progress = findViewById<ProgressBar>(R.id.downloadProgress)
        val btnDownload = findViewById<MaterialButton>(R.id.btnDownload)
        val btnSelect = findViewById<MaterialButton>(R.id.btnSelect)

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

        btnDownload.setOnClickListener {
            val tier = selectedTier()
            if (cache.freeBytes() < tier.approxBytes + 5L * 1024 * 1024) {
                Toast.makeText(this, getString(R.string.error_storage), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            progress.visibility = View.VISIBLE
            progress.progress = 0
            btnDownload.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ModelDownloadManager(cache).download(tier) { p ->
                        runOnUiThread { progress.progress = p.percent }
                    }
                }
                btnDownload.isEnabled = true
                progress.visibility = View.GONE
                if (result.isSuccess) {
                    app.settings.update { it.copy(modelTierId = tier.id) }
                    Toast.makeText(this@ModelManagerActivity, getString(R.string.model_ready), Toast.LENGTH_SHORT).show()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: getString(R.string.error_model)
                    Toast.makeText(this@ModelManagerActivity, msg, Toast.LENGTH_LONG).show()
                }
                refreshStatus(status)
            }
        }
    }

    private fun selectedTier(): ModelTier = when (findViewById<RadioGroup>(R.id.modelGroup).checkedRadioButtonId) {
        R.id.radioFast -> ModelTier.FAST
        R.id.radioAccurate -> ModelTier.ACCURATE
        else -> ModelTier.BALANCED
    }

    private fun refreshStatus(status: TextView) {
        val tier = selectedTier()
        val present = cache.isPresent(tier)
        status.text = buildString {
            append(tier.displayName)
            append(": ")
            append(if (present) getString(R.string.model_ready) else getString(R.string.model_missing))
            append("\nURL: ")
            append(tier.downloadUrl)
            append("\nDemo engine works without the file; download enables future native ASR.")
        }
    }
}
