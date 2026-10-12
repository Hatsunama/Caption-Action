package com.hatsunama.captionaction.ui.home

import android.graphics.Typeface
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.data.AppSettings
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.EnsureResult
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import com.hatsunama.captionaction.service.CaptionOverlayService
import com.hatsunama.captionaction.service.LiveCaptionStarter
import com.hatsunama.captionaction.service.ModelDownloadManager
import com.hatsunama.captionaction.ui.thanks.ThankYouActivity
import com.hatsunama.captionaction.ui.mic.MicTranslatorActivity
import com.hatsunama.captionaction.util.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {

    private lateinit var cache: ModelCache
    private var downloadJob: Job? = null
    private var activeDownloader: ModelDownloadManager? = null
    private var suppressPersist = true
    private var fontIndex = 0
    private var modelGateDialog: AlertDialog? = null
    private var closeModelGate: (() -> Unit)? = null
    private var startingDialog: AlertDialog? = null
    private var mtPrepareJob: Job? = null
    private var mtEngine: MlKitTranslationEngine? = null
    private data class MtRequest(val target: String, val sources: Set<String>)
    private var statusSettings: AppSettings? = null
    private var mtRequest: MtRequest? = null
    private var mtGeneration = 0L
    private var mtAttemptedGeneration = -1L
    private var mtStatusLine = ""
    private var homeDestroyed = false
    private var refractBackground: RefractBackgroundView? = null

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
        dismissStartingDialog()
        // RESULT_OK is -1; StartHandoffGate treats any non-zero + data as granted.
        if (com.hatsunama.captionaction.service.StartHandoffGate.isProjectionResultGranted(
                result.resultCode,
                result.data != null
            )
        ) {
            LiveCaptionStarter.startWithProjectionAndMinimize(this, result.resultCode, result.data!!)
        } else {
            // Device audio only — never start mic when screen share is declined.
            LiveCaptionStarter.failProjectionDeclined(this)
        }
    }


    private val micAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startActivity(Intent(this, MicTranslatorActivity::class.java))
        } else {
            Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Charcoal under live refract so no pink flash before first draw.
        window.setBackgroundDrawableResource(R.color.ca_refract_charcoal)
        setContentView(R.layout.activity_home)
        cache = ModelCache(this)
        refractBackground = findViewById(R.id.refractBackground)
        // Arm early; View also re-arms on attach / first layout / window visible.
        refractBackground?.setAnimating(true)

        bindLanguageSection()
        bindCaptionsSection()
        bindOverlaySection()
        bindStartButton()
        bindMicTranslatorButton()
        bindThankYouButton()
        bindStatus()
    }

    override fun onResume() {
        super.onResume()
        // Resume locked refract sim from frozen t (never reset on pause).
        // Always re-arm even if View thinks it is already animating (callback-drop race).
        refractBackground?.setAnimating(true)
        // Opening Home while captions run stops them — but never during Start
        // handoff / recently-started session (Seeker: goToLauncherHome → Home resume).
        val running = CaptionOverlayService.instance != null
        if (!LiveCaptionStarter.shouldAutoStopOnHomeResume(running)) return
        CaptionOverlayService.stop(this)
    }

    override fun onPause() {
        // Freeze Choreographer clock; keep node state + tSec for seamless resume.
        refractBackground?.setAnimating(false)
        super.onPause()
    }

    override fun onDestroy() {
        homeDestroyed = true
        mtGeneration++ // Invalidate progress already queued on the main looper.
        closeModelGate?.invoke()
        activeDownloader?.cancel()
        downloadJob?.cancel()
        mtPrepareJob?.cancel()
        mtEngine?.release()
        mtEngine = null
        modelGateDialog?.dismiss()
        dismissStartingDialog()
        super.onDestroy()
    }

    private fun app(): CaptionActionApp = application as CaptionActionApp

    private fun bindStatus() {
        lifecycleScope.launch {
            app().settings.settingsFlow.collectLatest { s ->
                updateStatus(s)
                cache.verify(ModelTier.fromId(s.modelTierId))
                if (!homeDestroyed) renderStatus()
            }
        }
    }

    private fun updateStatus(s: AppSettings, retryMt: Boolean = false) {
        statusSettings = s
        val target = s.targetLanguage.trim().lowercase(java.util.Locale.ROOT)
        val input = s.inputLanguage.trim().lowercase(java.util.Locale.ROOT)
        val supported = target in InferenceEngineFactory.offlineTranslationTargets(ModelTier.fromId(s.modelTierId))
        val next = if (supported && input.isNotEmpty() && input != target) {
            MtRequest(target, setOf(input))
        } else null
        if (next != mtRequest || (retryMt && mtPrepareJob?.isActive != true)) {
            mtRequest = next
            mtGeneration++
            mtStatusLine = getString(R.string.translation_pack_preparing)
        }
        renderStatus()
        ensureMtPacks()
    }

    /** All status paths use the same current-settings snapshot and model-cache checks. */
    private fun renderStatus() {
        if (homeDestroyed) return
        val s = statusSettings ?: return
        val tier = ModelTier.fromId(s.modelTierId)
        val present = cache.isPresent(tier)
        val overlayOk = LiveCaptionStarter.canDrawOverlays(this@HomeActivity)
        val mtTargets = InferenceEngineFactory.offlineTranslationTargets(tier)
        val target = s.targetLanguage.trim().lowercase(java.util.Locale.ROOT)
        val input = s.inputLanguage.trim().lowercase(java.util.Locale.ROOT)
        val needsMt = target.isNotEmpty() && input.isNotEmpty() && input != target
        findViewById<TextView>(R.id.statusText).text = buildString {
            append("Model: ${tier.displayName}")
            append(
                when {
                    present -> " ✓ on device. "
                    cache.isPartial(tier) -> " · partial (can resume). "
                    else -> " · not downloaded. "
                }
            )
            append(if (overlayOk) "Overlay ready." else "Overlay permission still needed.")
            val dualEngineOk = InferenceEngineFactory.canProvideDualSubtitles(tier, s.targetLanguage)
            val dualOk = dualEngineOk && needsMt
            append(" Dual: ").append(
                when {
                    !dualOk -> "unavailable"
                    s.dualSubtitles -> "on"
                    else -> "off"
                }
            )
            append(" · Input: ").append(s.inputLanguage)
            append(" · Target: ").append(s.targetLanguage)
            append(" · MT: ")
            when {
                !needsMt -> append("ASR-only (input=target)")
                target in mtTargets -> append(mtStatusLine.ifBlank { getString(R.string.translation_pack_preparing) })
                else -> append(getString(R.string.translation_pack_unsupported, s.targetLanguage))
            }
        }
    }

    private fun ensureMtPacks() {
        if (homeDestroyed || mtPrepareJob?.isActive == true) return
        val request = mtRequest ?: return
        val generation = mtGeneration
        if (mtAttemptedGeneration == generation) return
        mtAttemptedGeneration = generation
        val engine = mtEngine ?: MlKitTranslationEngine(this).also { mtEngine = it }
        mtPrepareJob = lifecycleScope.launch {
            try {
                mtStatusLine = getString(R.string.translation_pack_downloading)
                renderStatus()
                val result = withContext(Dispatchers.IO) {
                    engine.ensureModels(request.target, request.sources) { line ->
                        runOnUiThread {
                            if (!homeDestroyed && mtGeneration == generation && mtPrepareJob?.isActive == true) {
                                mtStatusLine = line
                                renderStatus()
                            }
                        }
                    }
                }
                if (!homeDestroyed && mtGeneration == generation) {
                    mtStatusLine = when (result) {
                        is EnsureResult.Ready -> getString(R.string.translation_pack_ready)
                        is EnsureResult.Failed -> getString(R.string.translation_pack_failed, result.message)
                    }
                    renderStatus()
                }
            } finally {
                mtPrepareJob = null
                // A blocking SDK task may outlive a settings change. Drain only the
                // latest request after it returns; never relabel its old result.
                ensureMtPacks()
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
            showModelGateThenStart {
                lifecycleScope.launch { proceedAfterModelReady() }
            }
        }
    }

    private fun bindMicTranslatorButton() {
        findViewById<MaterialButton>(R.id.btnMicTranslator).setOnClickListener {
            showModelGateThenStart { openMicTranslatorAfterAudioPerm() }
        }
    }

    /** Shared whisper Tiny / BALANCED gate for Live Captions and Mic Translator. */
    private fun showModelGateThenStart(onReady: () -> Unit) {
        // Opening either entry replaces the previous gate and invalidates its callbacks.
        val previousDialog = modelGateDialog
        closeModelGate?.invoke()
        previousDialog?.dismiss()
        val session = ModelGateSession()
        var gateJob: Job? = null
        var gateVerification: Job? = null
        var verifiedForGate = false
        var verifying = true
        var gateDownloader: ModelDownloadManager? = null
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_model_gate, null)
        val group = view.findViewById<RadioGroup>(R.id.modelGroup)
        val status = view.findViewById<TextView>(R.id.modelStatus)
        val progress = view.findViewById<ProgressBar>(R.id.downloadProgress)
        val onceHint = view.findViewById<TextView>(R.id.downloadOnceHint)
        val percent = view.findViewById<TextView>(R.id.downloadPercent)
        val btnDownload = view.findViewById<MaterialButton>(R.id.btnDownload)
        val btnUse = view.findViewById<MaterialButton>(R.id.btnUseAndStart)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelGate)
        var downloading = false
        val radioBalanced = view.findViewById<RadioButton>(R.id.radioBalanced)
        val titleView = view.findViewById<TextView>(R.id.modelGateTitle)
        val bodyView = view.findViewById<TextView>(R.id.modelGateBody)
        // Live-only gate: Balanced (whisper Tiny) is the sole user-facing path.
        // ModelTier.FAST/SenseVoice remains available to the engine for compatibility,
        // but is intentionally not user-facing here.
        titleView?.setText(R.string.product_live_gate_title)
        bodyView?.setText(R.string.product_live_gate_body)
        radioBalanced.isChecked = true

        fun selectedTier(): ModelTier = ModelTier.BALANCED

        fun refreshStatus() {
            val tier = selectedTier()
            val ready = !verifying && verifiedForGate && cache.isReadyForAsr(tier)
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
                append("\n")
                append(getString(R.string.model_offline_mt_mlkit))
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

        radioBalanced.isChecked = true
        refreshStatus()

        group.setOnCheckedChangeListener { _, _ -> refreshStatus() }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        modelGateDialog = dialog

        fun closeGate() {
            session.close()
            gateVerification?.cancel()
            gateDownloader?.cancel()
            gateJob?.cancel()
            if (activeDownloader === gateDownloader) activeDownloader = null
            if (downloadJob === gateJob) downloadJob = null
            if (modelGateDialog === dialog) {
                closeModelGate = null
            }
        }
        closeModelGate = ::closeGate
        fun gateIsCurrent(): Boolean = session.isActive && dialog.isShowing && modelGateDialog === dialog
        dialog.setOnCancelListener { closeGate() }
        dialog.setOnDismissListener {
            closeGate()
            if (modelGateDialog === dialog) modelGateDialog = null
        }
        btnCancel.setOnClickListener {
            closeGate()
            dialog.dismiss()
        }

        btnUse.setOnClickListener {
            val tier = selectedTier()
            if (!gateIsCurrent() || verifying || !verifiedForGate || gateJob?.isActive == true) return@setOnClickListener
            btnUse.isEnabled = false
            gateJob = lifecycleScope.launch {
                val stillVerified = cache.verify(tier)
                if (!gateIsCurrent()) return@launch
                verifiedForGate = stillVerified
                if (!stillVerified) {
                    refreshStatus()
                    Toast.makeText(this@HomeActivity, getString(R.string.error_model), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                app().settings.update { it.copy(modelTierId = tier.id) }
                if (!gateIsCurrent()) return@launch
                session.complete {
                    dialog.dismiss()
                    onReady()
                }
            }
            downloadJob = gateJob
        }

        btnDownload.setOnClickListener {
            if (!gateIsCurrent() || downloading || gateJob?.isActive == true || !btnDownload.isEnabled) return@setOnClickListener
            val tier = selectedTier()
            gateVerification?.cancel()
            verifying = false
            verifiedForGate = false
            val already = cache.partialBytes(tier)
            val resuming = already > 0L && cache.isPartial(tier)
            // The downloader verifies existing complete files before exact-size storage checks.
            onceHint.visibility = View.VISIBLE
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
            gateDownloader = mgr
            activeDownloader = mgr
            gateJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    mgr.download(tier) { p ->
                        runOnUiThread {
                            if (!gateIsCurrent() || gateDownloader !== mgr) return@runOnUiThread
                            onceHint.visibility = View.VISIBLE
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
                if (!gateIsCurrent()) return@launch
                downloading = false
                verifiedForGate = result.isSuccess && cache.isReadyForAsr(tier)
                group.isEnabled = true
                for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = true
                if (activeDownloader === mgr) activeDownloader = null
                refreshStatus()
                when {
                    result.isSuccess -> {
                        app().settings.update { it.copy(modelTierId = tier.id) }
                        if (!gateIsCurrent()) return@launch
                        Toast.makeText(this@HomeActivity, getString(R.string.model_ready), Toast.LENGTH_SHORT).show()
                        refreshStatus()
                        if (cache.isReadyForAsr(tier)) {
                            session.complete {
                                dialog.dismiss()
                                onReady()
                            }
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
                        val error = result.exceptionOrNull()
                        val msg = if (error is ModelDownloadManager.StorageException) {
                            getString(R.string.error_storage)
                        } else error?.message ?: getString(R.string.error_model)
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                        progress.visibility = View.GONE
                        percent.visibility = View.GONE
                        refreshStatus()
                    }
                }
            }
            downloadJob = gateJob
        }

        dialog.show()
        gateVerification = lifecycleScope.launch {
            val verified = cache.verify(selectedTier())
            if (!gateIsCurrent()) return@launch
            verifiedForGate = verified
            verifying = false
            refreshStatus()
            renderStatus()
        }
    }


    private fun openMicTranslatorAfterAudioPerm() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startActivity(Intent(this, MicTranslatorActivity::class.java))
        } else {
            micAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private suspend fun proceedAfterModelReady() {
        // Never gate Start on ML Kit packs — show loading UI, kick MT in background,
        // then launch projection / playback capture immediately after ASR model is ready.
        showStartingDialog(getString(R.string.starting_captions))
        val settings = app().settings.current()
        // Home optional MT status only — do not await.
        updateStatus(settings, retryMt = true)
        if (LiveCaptionStarter.needsPermissionWalkthrough(settings, this)) {
            dismissStartingDialog()
            permissionFlow.launch(LiveCaptionStarter.permissionStepIntent(this))
            return
        }
        // Live phone state BEFORE share-one-app / entire-screen UI — never assume
        // a prior grant still holds; if overlay/RECORD_AUDIO dropped, re-walk.
        if (!LiveCaptionStarter.liveCapturePrechecksOk(this)) {
            dismissStartingDialog()
            permissionFlow.launch(LiveCaptionStarter.permissionStepIntent(this))
            return
        }
        showStartingDialog(getString(R.string.starting_requesting_capture))
        LiveCaptionStarter.beginStartHandoff()
        if (LiveCaptionStarter.shouldRequestProjection()) {
            // Fresh createScreenCaptureIntent every Start — no stale RESULT_OK reuse.
            dismissStartingDialog()
            projectionLauncher.launch(LiveCaptionStarter.createScreenCaptureIntent(this))
        } else {
            // Pre-Q: no AudioPlaybackCapture — product is device audio only.
            dismissStartingDialog()
            LiveCaptionStarter.failRequiresAndroid10(this)
        }
    }

    private fun showStartingDialog(status: String) {
        val existing = startingDialog
        if (existing != null && existing.isShowing) {
            existing.findViewById<TextView>(R.id.startingStatus)?.text = status
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_starting, null)
        view.findViewById<TextView>(R.id.startingStatus).text = status
        startingDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dismissStartingDialog() {
        startingDialog?.dismiss()
        startingDialog = null
    }

    private fun bindLanguageSection() {
        val spinnerInput = findViewById<Spinner>(R.id.spinnerInput)
        val spinnerTarget = findViewById<Spinner>(R.id.spinnerTarget)
        val labels = Languages.all.map { it.label }
        spinnerInput.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        spinnerTarget.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val dual = findViewById<MaterialSwitch>(R.id.switchDual)
        val dualHint = findViewById<TextView>(R.id.dualHint)

        fun refreshDualAvailability(
            inputCode: String,
            targetCode: String,
            tierId: String,
            savedDual: Boolean
        ) {
            val tier = ModelTier.fromId(tierId)
            val engineOk = InferenceEngineFactory.canProvideDualSubtitles(tier, targetCode)
            val inputDiffers = inputCode.trim().lowercase() != targetCode.trim().lowercase()
            val available = engineOk && inputDiffers
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
                val input = Languages.all.getOrNull(spinnerInput.selectedItemPosition)?.code
                    ?: s.inputLanguage
                val target = Languages.all.getOrNull(spinnerTarget.selectedItemPosition)?.code
                    ?: s.targetLanguage
                refreshDualAvailability(input, target, s.modelTierId, s.dualSubtitles)
            }
        }

        lifecycleScope.launch {
            val s = app().settings.current()
            suppressPersist = true
            spinnerInput.setSelection(
                Languages.all.indexOfFirst { it.code == s.inputLanguage }.coerceAtLeast(0)
            )
            spinnerTarget.setSelection(
                Languages.all.indexOfFirst { it.code == s.targetLanguage }.coerceAtLeast(0)
            )
            refreshDualAvailability(
                s.inputLanguage,
                s.targetLanguage,
                s.modelTierId,
                s.dualSubtitles
            )
            suppressPersist = false
        }

        val selectionListener = object : android.widget.AdapterView.OnItemSelectedListener {
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
        spinnerInput.onItemSelectedListener = selectionListener
        spinnerTarget.onItemSelectedListener = selectionListener
        dual.setOnCheckedChangeListener { _, _ -> if (!suppressPersist) persistLanguageSettings() }
    }

    private fun persistLanguageSettings() {
        val spinnerInput = findViewById<Spinner>(R.id.spinnerInput)
        val spinnerTarget = findViewById<Spinner>(R.id.spinnerTarget)
        val dual = findViewById<MaterialSwitch>(R.id.switchDual)
        val input = Languages.all.getOrNull(spinnerInput.selectedItemPosition)?.code ?: "en"
        val target = Languages.all.getOrNull(spinnerTarget.selectedItemPosition)?.code ?: "en"
        lifecycleScope.launch {
            val tierId = app().settings.current().modelTierId
            val engineOk = InferenceEngineFactory.canProvideDualSubtitles(
                ModelTier.fromId(tierId),
                target
            )
            val inputDiffers = input.trim().lowercase() != target.trim().lowercase()
            val dualOk = engineOk && inputDiffers
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
                    inputLanguage = input,
                    targetLanguage = target,
                    passthroughLanguages = emptySet(),
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
