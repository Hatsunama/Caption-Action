package com.hatsunama.captionaction.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ProgressBar
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.service.ModelDownloadManager
import com.hatsunama.captionaction.ui.permissions.PermissionStepActivity
import com.hatsunama.captionaction.util.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class HomeActivity : AppCompatActivity() {

    private lateinit var cache: ModelCache
    private val selectedPassthrough = mutableSetOf<String>()
    private var downloadJob: Job? = null
    private var activeDownloader: ModelDownloadManager? = null
    private var suppressPersist = true

    private var boxX = 40
    private var boxY = 80
    private var boxW = 600
    private var boxH = 140
    private var fontIndex = 0

    private enum class TouchMode { NONE, MOVE, RESIZE }

    private val permissionFlow = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.permission_setup_done), Toast.LENGTH_LONG).show()
        }
        refreshStartButton()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            CaptionOverlayService.start(this, result.resultCode, result.data)
            refreshStartButton()
            // Minimize so captions appear over other apps immediately.
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, getString(R.string.permission_projection_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        cache = ModelCache(this)

        bindLanguageSection()
        bindModelSection()
        bindOverlaySection()
        bindStartButton()
        bindStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStartButton()
        refreshModelStatus()
    }

    override fun onDestroy() {
        activeDownloader?.cancel()
        downloadJob?.cancel()
        super.onDestroy()
    }

    private fun app(): CaptionActionApp = application as CaptionActionApp

    private fun bindStatus() {
        val status = findViewById<TextView>(R.id.statusText)
        lifecycleScope.launch {
            app().settings.settingsFlow.collectLatest { s ->
                val tier = ModelTier.fromId(s.modelTierId)
                val present = cache.isPresent(tier)
                val overlayOk = canDrawOverlays()
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

    private fun bindStartButton() {
        findViewById<MaterialButton>(R.id.btnStartLive).setOnClickListener {
            if (CaptionOverlayService.instance != null) {
                CaptionOverlayService.stop(this)
                refreshStartButton()
                return@setOnClickListener
            }
            lifecycleScope.launch { onStartCaptions() }
        }
        refreshStartButton()
    }

    private suspend fun onStartCaptions() {
        val settings = app().settings.current()
        val needsWalkthrough = !settings.permissionsWalkthroughComplete || missingRequiredGrants()
        if (needsWalkthrough) {
            val mode = if (!settings.permissionsWalkthroughComplete) {
                PermissionStepActivity.MODE_SETUP
            } else {
                PermissionStepActivity.MODE_MISSING
            }
            permissionFlow.launch(
                Intent(this, PermissionStepActivity::class.java).putExtra(
                    PermissionStepActivity.EXTRA_MODE,
                    mode
                )
            )
            return
        }
        // Permissions already OK — request MediaProjection and start immediately.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } else {
            // Pre-Q: playback capture unavailable; emergency mic path only.
            CaptionOverlayService.start(this)
            moveTaskToBack(true)
        }
    }

    private fun missingRequiredGrants(): Boolean {
        if (!canDrawOverlays()) return true
        if (!hasAudioPermission()) return true
        return false
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshStartButton() {
        val running = CaptionOverlayService.instance != null
        findViewById<MaterialButton>(R.id.btnStartLive).text =
            if (running) getString(R.string.stop_live) else getString(R.string.start_live)
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
        val preferPlayback = findViewById<MaterialSwitch>(R.id.switchPreferPlayback)
        val summary = findViewById<TextView>(R.id.passthroughSummary)

        lifecycleScope.launch {
            val s = app().settings.current()
            selectedPassthrough.clear()
            selectedPassthrough.addAll(s.passthroughLanguages)
            suppressPersist = true
            spinner.setSelection(
                Languages.all.indexOfFirst { it.code == s.targetLanguage }.coerceAtLeast(0)
            )
            dual.isChecked = s.dualSubtitles
            preferPlayback.isChecked = s.preferPlaybackCapture
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
        preferPlayback.setOnCheckedChangeListener { _, _ -> if (!suppressPersist) persistLanguageSettings() }
    }

    private fun persistLanguageSettings() {
        val spinner = findViewById<Spinner>(R.id.spinnerTarget)
        val dual = findViewById<MaterialSwitch>(R.id.switchDual)
        val preferPlayback = findViewById<MaterialSwitch>(R.id.switchPreferPlayback)
        val target = Languages.all.getOrNull(spinner.selectedItemPosition)?.code ?: "en"
        lifecycleScope.launch {
            app().settings.update {
                it.copy(
                    targetLanguage = target,
                    passthroughLanguages = selectedPassthrough.toSet(),
                    dualSubtitles = dual.isChecked,
                    preferPlaybackCapture = preferPlayback.isChecked
                )
            }
        }
    }

    private fun bindModelSection() {
        val group = findViewById<RadioGroup>(R.id.modelGroup)
        val loading = findViewById<FrameLayout>(R.id.loadingOverlay)
        val progress = findViewById<ProgressBar>(R.id.downloadProgress)
        val percent = findViewById<TextView>(R.id.downloadPercent)
        val btnDownload = findViewById<MaterialButton>(R.id.btnDownload)
        val btnSelect = findViewById<MaterialButton>(R.id.btnSelectTier)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancelDownload)

        lifecycleScope.launch {
            val s = app().settings.current()
            when (ModelTier.fromId(s.modelTierId)) {
                ModelTier.FAST -> findViewById<RadioButton>(R.id.radioFast).isChecked = true
                ModelTier.BALANCED -> findViewById<RadioButton>(R.id.radioBalanced).isChecked = true
                ModelTier.ACCURATE -> findViewById<RadioButton>(R.id.radioAccurate).isChecked = true
            }
            refreshModelStatus()
        }

        group.setOnCheckedChangeListener { _, _ -> refreshModelStatus() }

        btnSelect.setOnClickListener {
            val tier = selectedTier()
            lifecycleScope.launch {
                app().settings.update { it.copy(modelTierId = tier.id) }
                Toast.makeText(this@HomeActivity, "Using ${tier.displayName}", Toast.LENGTH_SHORT).show()
                refreshModelStatus()
            }
        }

        btnCancel.setOnClickListener { activeDownloader?.cancel() }

        btnDownload.setOnClickListener {
            val tier = selectedTier()
            if (cache.freeBytes() < tier.approxBytes + 5L * 1024 * 1024) {
                Toast.makeText(this, getString(R.string.error_storage), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            loading.visibility = View.VISIBLE
            progress.progress = 0
            percent.text = "0%"
            btnDownload.isEnabled = false
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
                btnDownload.isEnabled = true
                activeDownloader = null
                when {
                    result.isSuccess -> {
                        app().settings.update { it.copy(modelTierId = tier.id) }
                        Toast.makeText(this@HomeActivity, getString(R.string.model_ready), Toast.LENGTH_SHORT).show()
                    }
                    result.exceptionOrNull() is ModelDownloadManager.CancelledException -> {
                        Toast.makeText(this@HomeActivity, "Download cancelled", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val msg = result.exceptionOrNull()?.message ?: getString(R.string.error_model)
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                refreshModelStatus()
            }
        }
    }

    private fun selectedTier(): ModelTier =
        when (findViewById<RadioGroup>(R.id.modelGroup).checkedRadioButtonId) {
            R.id.radioFast -> ModelTier.FAST
            R.id.radioAccurate -> ModelTier.ACCURATE
            else -> ModelTier.BALANCED
        }

    private fun refreshModelStatus() {
        val status = findViewById<TextView>(R.id.modelStatus) ?: return
        if (!::cache.isInitialized) return
        val tier = selectedTier()
        val present = cache.isPresent(tier)
        status.text = buildString {
            append(tier.displayName)
            append(": ")
            append(if (present) getString(R.string.model_ready) else getString(R.string.model_missing))
            append("\n")
            append(getString(R.string.demo_mode))
            append(" works without the file; download enables future native ASR.")
        }
    }

    private fun bindOverlaySection() {
        val area = findViewById<FrameLayout>(R.id.previewArea)
        val fontSpinner = findViewById<Spinner>(R.id.fontSpinner)
        fontSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Bold Sans", "Regular Sans", "Monospace")
        )

        val density = resources.displayMetrics.density
        val minW = (160 * density).toInt()
        val minH = (100 * density).toInt()
        val handleSize = (28 * density).toInt()

        val sample = TextView(this).apply {
            text = "Sample live caption\nDrag me · pull ▣ to resize"
            setTextColor(0xFF2D2140.toInt())
            setBackgroundResource(R.drawable.bg_overlay_bubble)
            setPadding(24, 16, 24, 16)
            textSize = 18f
        }
        val handle = View(this).apply {
            setBackgroundResource(R.drawable.bg_resize_handle)
        }

        val lp = FrameLayout.LayoutParams(boxW, boxH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = boxX
            topMargin = boxY
        }
        val handleLp = FrameLayout.LayoutParams(handleSize, handleSize).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        area.addView(sample, lp)
        area.addView(handle, handleLp)

        fun syncHandle() {
            val p = sample.layoutParams as FrameLayout.LayoutParams
            handleLp.leftMargin = p.leftMargin + p.width - handleSize / 2
            handleLp.topMargin = p.topMargin + p.height - handleSize / 2
            handle.layoutParams = handleLp
            val sp = ((p.height / density) / 7.5f).coerceIn(14f, 40f)
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

        lifecycleScope.launch {
            val s = app().settings.current()
            boxX = s.overlayX
            boxY = s.overlayY
            boxW = s.overlayWidth
            boxH = s.overlayHeight
            fontIndex = s.fontIndex
            suppressPersist = true
            fontSpinner.setSelection(fontIndex.coerceIn(0, 2))
            suppressPersist = false
            lp.width = max(minW, boxW)
            lp.height = max(minH, boxH)
            lp.leftMargin = boxX.coerceAtLeast(0)
            lp.topMargin = boxY.coerceAtLeast(0)
            sample.layoutParams = lp
            syncHandle()
        }

        var mode = TouchMode.NONE
        var lastX = 0f
        var lastY = 0f

        fun onTouch(v: View, e: MotionEvent, forceResize: Boolean): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    mode = if (forceResize || v === handle) TouchMode.RESIZE else TouchMode.MOVE
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).toInt()
                    val dy = (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    val p = sample.layoutParams as FrameLayout.LayoutParams
                    when (mode) {
                        TouchMode.MOVE -> {
                            p.leftMargin = (p.leftMargin + dx).coerceAtLeast(0)
                            p.topMargin = (p.topMargin + dy).coerceAtLeast(0)
                        }
                        TouchMode.RESIZE -> {
                            p.width = min(area.width.coerceAtLeast(minW), max(minW, p.width + dx))
                            p.height = min(area.height.coerceAtLeast(minH), max(minH, p.height + dy))
                        }
                        else -> {}
                    }
                    sample.layoutParams = p
                    boxX = p.leftMargin
                    boxY = p.topMargin
                    boxW = p.width
                    boxH = p.height
                    syncHandle()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = TouchMode.NONE
                }
                else -> return false
            }
            return true
        }

        sample.setOnTouchListener { v, e -> onTouch(v, e, false) }
        handle.setOnTouchListener { v, e -> onTouch(v, e, true) }

        fontSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                fontIndex = position
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
                app().settings.update {
                    it.copy(
                        overlayX = boxX,
                        overlayY = boxY,
                        overlayWidth = boxW,
                        overlayHeight = boxH,
                        fontIndex = fontIndex
                    )
                }
                Toast.makeText(this@HomeActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
