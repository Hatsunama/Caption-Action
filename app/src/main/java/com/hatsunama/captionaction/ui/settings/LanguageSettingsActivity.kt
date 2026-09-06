package com.hatsunama.captionaction.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.util.Languages
import kotlinx.coroutines.launch

class LanguageSettingsActivity : AppCompatActivity() {
    private val selectedPassthrough = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)
        val app = application as CaptionActionApp

        val spinner = findViewById<Spinner>(R.id.spinnerTarget)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Languages.all.map { it.label }
        )
        val chips = findViewById<ChipGroup>(R.id.passthroughChips)
        val dual = findViewById<MaterialSwitch>(R.id.switchDual)
        val preferPlayback = findViewById<MaterialSwitch>(R.id.switchPreferPlayback)
        val summary = findViewById<TextView>(R.id.passthroughSummary)

        lifecycleScope.launch {
            val s = app.settings.current()
            selectedPassthrough.clear()
            selectedPassthrough.addAll(s.passthroughLanguages)
            spinner.setSelection(
                Languages.all.indexOfFirst { it.code == s.targetLanguage }.coerceAtLeast(0)
            )
            dual.isChecked = s.dualSubtitles
            preferPlayback.isChecked = s.preferPlaybackCapture
            chips.removeAllViews()
            Languages.all.forEach { lang ->
                val chip = Chip(this@LanguageSettingsActivity).apply {
                    text = lang.label
                    isCheckable = true
                    isChecked = lang.code in selectedPassthrough
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedPassthrough.add(lang.code)
                        else selectedPassthrough.remove(lang.code)
                        summary.text = selectedPassthrough.joinToString(", ") { Languages.label(it) }
                    }
                }
                chips.addView(chip)
            }
            summary.text = selectedPassthrough.joinToString(", ") { Languages.label(it) }
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val target = Languages.all[spinner.selectedItemPosition].code
            lifecycleScope.launch {
                app.settings.update {
                    it.copy(
                        targetLanguage = target,
                        passthroughLanguages = selectedPassthrough.toSet(),
                        dualSubtitles = dual.isChecked,
                        preferPlaybackCapture = preferPlayback.isChecked
                    )
                }
                Toast.makeText(this@LanguageSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
