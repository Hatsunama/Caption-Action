package com.hatsunama.captionaction.ui.home

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.service.LiveCaptionStarter
import com.hatsunama.captionaction.service.ModelDownloadManager
import com.hatsunama.captionaction.ui.thanks.ThankYouActivity
import com.hatsunama.captionaction.util.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private lateinit var cache: ModelCache
    private val selectedPassthrough = mutableSetOf<String>()
    private var downloadJob: Job? = null
    private var activeDownloader: ModelDownloadManager? = null
    private var suppressPersist = true
    private var fontIndex = 0
    private var modelGateDialog: AlertDialog? = null

    private val permissionFlow = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.permission_setup_done), Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            LiveCaptionStarter.startWithProjectionAndMinimize(this, result.resultCode, result.data!!)
        } else {
            LiveCaptionStarter.startMicFallbackAfterDecline(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        cache = ModelCache(this)

        bindLanguageSection()
        bindCaptionsSection()
        bindOverlaySection()
        bindStartButton()
        bindThankYouButton()
        bindStatus()
    }

    override fun onResume() {
        super.onResume()
        if (CaptionOverlayService.instance != null) {
            CaptionOverlayService.stop(this)
        }
    }

    override fun onDestroy() {
        activeDownloader?.cancel()
        downloadJob?.cancel()
        modelGateDialog?.dismiss()
        super.onDestroy()
    }

    private fun app(): CaptionActionApp = application as CaptionActionApp

    private fun bindStatus() {
        val status = findViewById<TextView>(R.id.statusText)
        lifecycleScope.launch {
            app().settings.settingsFlow.collectLatest { s ->
                val tier = ModelTier.fromId(s.modelTierId)
                val present = cache.isPresent(tier)
                val overlayOk = LiveCaptionStarter.canDrawOverlays(this@HomeActivity)
                val mtTargets = InferenceEngineFactory.offlineTranslationTargets(tier)
                val target = s.targetLanguage.trim().lowercase()
                status.text = buildString {
                    append("Model: ${tier.displayName}")
                    append(
                        when {
                            present -> " ✓ on device. "
                            cache.isPartial(tier) -> " · partial (can resume). "
                            else -> " · not downloaded. "
                        }
                    )
                    append(if (overlayOk) "Overlay ready." else "Overlay permission still needed.")
                    val dualOk = InferenceEngineFactory.canProvideDualSubtitles(tier, s.targetLanguage)
                    append(" Dual: ").append(
                        when {
                            !dualOk -> "unavailable"
                            s.dualSubtitles -> "on"
                            else -> "off"
                        }
                    )
                    append(" · Target: ").append(s.targetLanguage)
                    if (target.isNotEmpty() && target !in mtTargets && target !in s.passthroughLanguages.map { it.lowercase() }) {
                        append("\n")
                        append(getString(R.string.translation_unavailable_hint, s.targetLanguage, tier.displayName))
                    }
                }
            }
        }
    }

    private fun bindThankYouButton() {
        findViewById<MaterialButton>(R.id.btnThankYou).setOnClickListener {
            startActivity(android.content.Intent(this, ThankYouActivity::class.java))
        }
    }

    private fun bindStartButton() {
        findViewById<MaterialButton>(R.id.btnStartLive).setOnClickListener {
            showModelGateThenStart()
        }
    }

    private fun showModelGateThenStart() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_model_gate, null)
        val group = view.findViewById<RadioGroup>(R.id.modelGroup)
        val status = view.findViewById<TextView>(R.id.modelStatus)
        val progress = view.findViewById<ProgressBar>(R.id.downloadProgress)
        val percent = view.findViewById<TextView>(R.id.downloadPercent)
        val btnDownload = view.findViewById<MaterialButton>(R.id.btnDownload)
        val btnUse = view.findViewById<MaterialButton>(R.id.btnUseAndStart)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelGate)
        var downloading = false

        fun selectedTier(): ModelTier = when (group.checkedRadioButtonId) {
            R.id.radioFast -> ModelTier.FAST
            R.id.radioAccurate -> ModelTier.ACCURATE
            else -> ModelTier.BALANCED
        }

        fun refreshStatus() {
            val tier = selectedTier()
            val ready = cache.isReadyForAsr(tier)
            val partial = cache.isPartial(tier)
            status.text = buildString {
                append(tier.displayName)
                append(": ")
                when {
                    ready -> append(getString(R.string.model_ready))
                    partial -> {
                        append(getString(R.string.model_partial))
                        append(" (")
                        append(cache.partialBytes(tier) / (1024 * 1024))
                        append(" MB)")
                    }
                    else -> append(getString(R.string.model_missing))
                }
                append("\n")
                append(getString(R.string.model_gate_download_required))
                val mt = InferenceEngineFactory.offlineTranslationTargets(tier)
                if (mt.isEmpty()) {
                    append("\n")
                    append(getString(R.string.model_no_offline_mt))
                } else {
                    append("\n")
                    append(getString(R.string.model_offline_mt_en_only))
                }
            }
            btnUse.isEnabled = ready && !downloading
            btnUse.alpha = if (ready && !downloading) 1f else 0.45f
            when {
                downloading -> {
                    btnDownload.isEnabled = false
                    btnDownload.alpha = 0.45f
                }
                ready -> {
                    btnDownload.isEnabled = false
                    btnDownload.alpha = 0.45f
                    btnDownload.text = getString(R.string.download_already_on_device)
                }
                partial -> {
                    btnDownload.isEnabled = true
                    btnDownload.alpha = 1f
                    btnDownload.text = getString(R.string.resume_download)
                }
                else -> {
                    btnDownload.isEnabled = true
                    btnDownload.alpha = 1f
                    btnDownload.text = getString(R.string.download_model)
                }
            }
        }

        lifecycleScope.launch {
            val s = app().settings.current()
            when (ModelTier.fromId(s.modelTierId)) {
                ModelTier.FAST -> view.findViewById<RadioButton>(R.id.radioFast).isChecked = true
                ModelTier.BALANCED -> view.findViewById<RadioButton>(R.id.radioBalanced).isChecked = true
                ModelTier.ACCURATE -> view.findViewById<RadioButton>(R.id.radioAccurate).isChecked = true
            }
            refreshStatus()
        }

        group.setOnCheckedChangeListener { _, _ -> refreshStatus() }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        modelGateDialog = dialog

        btnCancel.setOnClickListener {
            activeDownloader?.cancel()
            dialog.dismiss()
        }

        btnUse.setOnClickListener {
            val tier = selectedTier()
            if (!cache.isReadyForAsr(tier)) {
                Toast.makeText(this, getString(R.string.error_model), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                app().settings.update { it.copy(modelTierId = tier.id) }
                dialog.dismiss()
                proceedAfterModelReady()
            }
        }

        btnDownload.setOnClickListener {
            if (downloading || !btnDownload.isEnabled) return@setOnClickListener
            val tier = selectedTier()
            if (cache.isReadyForAsr(tier)) {
                Toast.makeText(this, getString(R.string.download_already_on_device), Toast.LENGTH_SHORT).show()
                refreshStatus()
                return@setOnClickListener
            }
            val already = cache.partialBytes(tier)
            val resuming = already > 0L && cache.isPartial(tier)
            val need = (tier.approxBytes - already).coerceAtLeast(0L)
            if (cache.freeBytes() < need + 5L * 1024 * 1024) {
                Toast.makeText(this, getString(R.string.error_storage), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            progress.visibility = View.VISIBLE
            percent.visibility = View.VISIBLE
            progress.isIndeterminate = false
            val startPct = if (tier.approxBytes > 0 && already > 0) {
                ((already * 100) / tier.approxBytes).toInt().coerceIn(0, 99)
            } else 0
            progress.progress = startPct
            val totalMb = (tier.approxBytes / (1024 * 1024)).toInt().coerceAtLeast(1)
            val haveMb = (already / (1024 * 1024)).toInt()
            percent.text = getString(R.string.download_progress_mb, startPct, haveMb, totalMb)
            status.text = if (resuming) {
                getString(R.string.download_resuming) + "\n" + getString(R.string.download_connecting)
            } else {
                getString(R.string.download_starting) + "\n" + getString(R.string.download_connecting)
            }
            btnDownload.text = if (resuming) {
                getString(R.string.download_resuming)
            } else {
                getString(R.string.download_in_progress)
            }
            Toast.makeText(
                this,
                if (resuming) getString(R.string.download_resuming) else getString(R.string.download_starting),
                Toast.LENGTH_SHORT
            ).show()
            downloading = true
            btnDownload.isEnabled = false
            btnDownload.alpha = 0.45f
            btnUse.isEnabled = false
            group.isEnabled = false
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = false
            val mgr = ModelDownloadManager(cache)
            activeDownloader = mgr
            downloadJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    mgr.download(tier) { p ->
                        runOnUiThread {
                            progress.visibility = View.VISIBLE
                            percent.visibility = View.VISIBLE
                            progress.isIndeterminate = false
                            progress.progress = p.percent
                            val have = (p.bytesRead / (1024 * 1024)).toInt()
                            val tot = (p.totalBytes / (1024 * 1024)).toInt().coerceAtLeast(1)
                            percent.text = getString(R.string.download_progress_mb, p.percent, have, tot)
                            status.text = getString(R.string.download_in_progress) +
                                " · " + p.percent + "%"
                        }
                    }
                }
                downloading = false
                group.isEnabled = true
                for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = true
                activeDownloader = null
                refreshStatus()
                when {
                    result.isSuccess -> {
                        app().settings.update { it.copy(modelTierId = tier.id) }
                        Toast.makeText(this@HomeActivity, getString(R.string.model_ready), Toast.LENGTH_SHORT).show()
                        refreshStatus()
                        if (cache.isReadyForAsr(tier)) {
                            dialog.dismiss()
                            proceedAfterModelReady()
                        }
                    }
                    result.exceptionOrNull() is ModelDownloadManager.CancelledException -> {
                        Toast.makeText(
                            this@HomeActivity,
                            getString(R.string.download_can_resume),
                            Toast.LENGTH_SHORT
                        ).show()
                        progress.visibility = View.GONE
                        percent.visibility = View.GONE
                        refreshStatus()
                    }
                    else -> {
                        val msg = result.exceptionOrNull()?.message ?: getString(R.string.error_model)
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                        progress.visibility = View.GONE
                        percent.visibility = View.GONE
                        refreshStatus()
                    }
                }
            }
        }

        dialog.show()
    }

    private suspend fun proceedAfterModelReady() {
        val settings = app().settings.current()
        if (LiveCaptionStarter.needsPermissionWalkthrough(settings, this)) {
            permissionFlow.launch(LiveCaptionStarter.permissionStepIntent(this))
            return
        }
        if (LiveCaptionStarter.shouldRequestProjection()) {
            projectionLauncher.launch(LiveCaptionStarter.createScreenCaptureIntent(this))
        } else {
            LiveCaptionStarter.startMicAndMinimize(this)
        }
    }

    private fun bindLanguageSection() {
        val spinner = findViewById<Spinner>(R.id.spinnerTarget)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Languages.all.map { it.label }
        )
        val chips = findViewById<ChipGroup>(R.id.passthroughChips)
        val dual = findViewById<MaterialSwitch>(R.id.switchDual)
        val dualHint = findViewById<TextView>(R.id.dualHint)
        val summary = findViewById<TextView>(R.id.passthroughSummary)

        fun refreshDualAvailability(targetCode: String, tierId: String, savedDual: Boolean) {
            val tier = ModelTier.fromId(tierId)
            val available = InferenceEngineFactory.canProvideDualSubtitles(tier, targetCode)
            dual.visibility = if (available) View.VISIBLE else View.GONE
            dualHint.visibility = if (available) View.GONE else View.VISIBLE
            dualHint.text = getString(R.string.dual_unavailable_hint)
            if (!available) {
                if (dual.isChecked) dual.isChecked = false
                if (savedDual) {
                    lifecycleScope.launch {
                        app().settings.update { it.copy(dualSubtitles = false) }
                    }
                }
            } else {
                dual.isChecked = savedDual
            }
        }

        lifecycleScope.launch {
            app().settings.settingsFlow.collectLatest { s ->
                if (suppressPersist) return@collectLatest
                val target = Languages.all.getOrNull(spinner.selectedItemPosition)?.code
                    ?: s.targetLanguage
                refreshDualAvailability(target, s.modelTierId, s.dualSubtitles)
            }
        }

        lifecycleScope.launch {
            val s = app().settings.current()
            selectedPassthrough.clear()
            selectedPassthrough.addAll(s.passthroughLanguages)
            suppressPersist = true
            spinner.setSelection(
                Languages.all.indexOfFirst { it.code == s.targetLanguage }.coerceAtLeast(0)
            )
            refreshDualAvailability(s.targetLanguage, s.modelTierId, s.dualSubtitles)
            chips.removeAllViews()
            Languages.all.forEach { lang ->
                val chip = Chip(this@HomeActivity).apply {
                    text = lang.label
                    isCheckable = true
                    isChecked = lang.code in selectedPassthrough
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedPassthrough.add(lang.code)
                        else selectedPassthrough.remove(lang.code)
                        summary.text = selectedPassthrough.joinToString(", ") { Languages.label(it) }
                        if (!suppressPersist) persistLanguageSettings()
                    }
                }
                chips.addView(chip)
            }
            summary.text = selectedPassthrough.joinToString(", ") { Languages.label(it) }
            suppressPersist = false
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!suppressPersist) persistLanguageSettings()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        dual.setOnCheckedChangeListener { _, _ -> if (!suppressPersist) persistLanguageSettings() }
    }

    private fun persistLanguageSettings() {
        val spinner = findViewById<Spinner>(R.id.spinnerTarget)
        val dual = findViewById<MaterialSwitch>(R.id.switchDual)
        val target = Languages.all.getOrNull(spinner.selectedItemPosition)?.code ?: "en"
        lifecycleScope.launch {
            val tierId = app().settings.current().modelTierId
            val dualOk = InferenceEngineFactory.canProvideDualSubtitles(
                ModelTier.fromId(tierId),
                target
            )
            val dualValue = dualOk && dual.isChecked
            if (!dualOk) {
                dual.visibility = View.GONE
                findViewById<TextView>(R.id.dualHint).visibility = View.VISIBLE
                dual.isChecked = false
            } else {
                dual.visibility = View.VISIBLE
                findViewById<TextView>(R.id.dualHint).visibility = View.GONE
            }
            app().settings.update {
                it.copy(
                    targetLanguage = target,
                    passthroughLanguages = selectedPassthrough.toSet(),
                    dualSubtitles = dualValue
                )
            }
        }
    }

    private fun bindCaptionsSection() {
        val saveSwitch = findViewById<MaterialSwitch>(R.id.switchSaveSubtitles)
        lifecycleScope.launch {
            val s = app().settings.current()
            saveSwitch.isChecked = s.saveSubtitlesToFile
            saveSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    app().settings.update { it.copy(saveSubtitlesToFile = checked) }
                }
            }
        }
    }

    private fun bindOverlaySection() {
        val fontSpinner = findViewById<Spinner>(R.id.fontSpinner)
        val sample = findViewById<TextView>(R.id.previewSample)
        fontSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Bold Sans", "Regular Sans", "Monospace")
        )

        fun applyPreviewFont(index: Int) {
            val tf = when (index) {
                1 -> Typeface.SANS_SERIF
                2 -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT_BOLD
            }
            sample.typeface = tf
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }

        lifecycleScope.launch {
            val s = app().settings.current()
            fontIndex = s.fontIndex
            suppressPersist = true
            fontSpinner.setSelection(fontIndex.coerceIn(0, 2))
            suppressPersist = false
            applyPreviewFont(fontIndex)
        }

        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                fontIndex = position
                applyPreviewFont(fontIndex)
                if (!suppressPersist) {
                    lifecycleScope.launch {
                        app().settings.update { it.copy(fontIndex = fontIndex) }
                    }
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<MaterialButton>(R.id.btnSaveOverlay).setOnClickListener {
            lifecycleScope.launch {
                app().settings.update { it.copy(fontIndex = fontIndex) }
                Toast.makeText(this@HomeActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
