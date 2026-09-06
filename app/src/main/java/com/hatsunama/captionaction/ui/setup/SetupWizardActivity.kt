package com.hatsunama.captionaction.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.service.ModelDownloadManager
import com.hatsunama.captionaction.ui.live.LiveSessionActivity
import com.hatsunama.captionaction.util.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupWizardActivity : AppCompatActivity() {
    private var step = 0
    private val totalSteps = 10

    private var chosenTier = ModelTier.BALANCED
    private var targetLang = "en"
    private val passthrough = mutableSetOf("en")
    private var dual = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStep() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            if (step > 0) {
                step--
                render()
            } else finish()
        }
        findViewById<MaterialButton>(R.id.btnNext).setOnClickListener { onNext() }
        render()
    }

    private fun onNext() {
        when (step) {
            2 -> requestRuntimePermissions()
            4 -> {
                // download selected model
                lifecycleScope.launch { downloadAndAdvance() }
                return
            }
            9 -> {
                lifecycleScope.launch {
                    (application as CaptionActionApp).settings.update {
                        it.copy(
                            setupComplete = true,
                            modelTierId = chosenTier.id,
                            targetLanguage = targetLang,
                            passthroughLanguages = passthrough.toSet(),
                            dualSubtitles = dual
                        )
                    }
                    startActivity(Intent(this@SetupWizardActivity, LiveSessionActivity::class.java))
                    finish()
                }
                return
            }
        }
        if (step < totalSteps - 1) {
            step++
            render()
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            step++
            render()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private suspend fun downloadAndAdvance() {
        val cache = ModelCache(this)
        if (cache.isPresent(chosenTier)) {
            step++
            render()
            return
        }
        Toast.makeText(this, "Downloading ${chosenTier.displayName}…", Toast.LENGTH_SHORT).show()
        val mgr = ModelDownloadManager(cache)
        val result = withContext(Dispatchers.IO) {
            mgr.download(chosenTier) { /* progress ignored in wizard for brevity */ }
        }
        if (result.isFailure) {
            Toast.makeText(
                this,
                result.exceptionOrNull()?.message ?: getString(R.string.error_storage),
                Toast.LENGTH_LONG
            ).show()
            // Allow continuing with demo engine
        }
        step++
        render()
    }

    private fun refreshStep() {
        render()
    }

    private fun render() {
        findViewById<TextView>(R.id.stepProgress).text = "Step ${step + 1} of $totalSteps"
        findViewById<MaterialButton>(R.id.btnNext).text =
            if (step == totalSteps - 1) getString(R.string.finish) else getString(R.string.next)
        val container = findViewById<android.widget.FrameLayout>(R.id.stepContainer)
        container.removeAllViews()
        val body = layoutInflater.inflate(R.layout.step_content, container, false)
        val stepBody = body.findViewById<LinearLayout>(R.id.stepBody)
        val title = findViewById<TextView>(R.id.stepTitle)

        when (step) {
            0 -> {
                title.text = "Welcome"
                addText(stepBody, "Caption Action — free, fully local live subtitle overlay.")
            }
            1 -> {
                title.text = "Privacy promise"
                addText(stepBody, getString(R.string.privacy_promise))
            }
            2 -> {
                title.text = "Permissions"
                addText(stepBody, "We need overlay, audio capture, and notification permissions. Storage is app-private for models.")
            }
            3 -> {
                title.text = "Choose model tier"
                val group = RadioGroup(this)
                ModelTier.entries.forEach { tier ->
                    val rb = RadioButton(this).apply {
                        text = "${tier.displayName} (~${tier.approxBytes / (1024 * 1024)} MB)"
                        id = View.generateViewId()
                        isChecked = tier == chosenTier
                        setOnClickListener { chosenTier = tier }
                    }
                    group.addView(rb)
                }
                stepBody.addView(group)
            }
            4 -> {
                title.text = "Download model"
                addText(stepBody, "One-time download of ${chosenTier.displayName}. After this, the app works offline.")
            }
            5 -> {
                title.text = "Subtitle language"
                val spinner = Spinner(this)
                val labels = Languages.all.map { it.label }
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                spinner.setSelection(Languages.all.indexOfFirst { it.code == targetLang }.coerceAtLeast(0))
                spinner.onItemSelectedListener = simpleSelect { pos ->
                    targetLang = Languages.all[pos].code
                }
                stepBody.addView(spinner)
            }
            6 -> {
                title.text = "Passthrough languages"
                addText(stepBody, "Leave these languages unchanged:")
                Languages.all.forEach { lang ->
                    val cb = CheckBox(this).apply {
                        text = lang.label
                        isChecked = lang.code in passthrough
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) passthrough.add(lang.code) else passthrough.remove(lang.code)
                        }
                    }
                    stepBody.addView(cb)
                }
            }
            7 -> {
                title.text = "Subtitle mode"
                val sw = MaterialSwitch(this).apply {
                    text = getString(R.string.dual_subtitles)
                    isChecked = dual
                    setOnCheckedChangeListener { _, v -> dual = v }
                }
                stepBody.addView(sw)
            }
            8 -> {
                title.text = "Overlay preview"
                addText(stepBody, "On the next screens you can drag/resize the overlay. For now, defaults are fine — open Overlay preview anytime from Home.")
            }
            9 -> {
                title.text = "Ready"
                addText(stepBody, "Start live captions. Models load from device storage on later launches.")
            }
        }
        container.addView(body)
    }

    private fun addText(parent: LinearLayout, msg: String) {
        parent.addView(TextView(this).apply {
            text = msg
            textSize = 16f
            setPadding(0, 8, 0, 8)
        })
    }

    private fun simpleSelect(onSelect: (Int) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = onSelect(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
}
