package com.hatsunama.captionaction.ui.mic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.audio.AudioCapture
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.AsrJunkFilter
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.EnsureResult
import com.hatsunama.captionaction.inference.InferenceEngine
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import com.hatsunama.captionaction.ui.home.RefractBackgroundView
import com.hatsunama.captionaction.util.Languages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * In-app microphone → whisper Tiny ASR (explicit From language) → ML Kit to To.
 * Isolated from Live Captions (which stays MediaProjection / playback-only, source=auto).
 *
 * Mic path uses FIFO drain (~1.5–2.0 s windows) with [forceFlush]=true so each
 * spoken window is transcribed in order (no newest-trim drops). Pump stays open
 * while [micOn]; ASR is serialized (never cancel/skip when infer is slow).
 *
 * From/To NumberPickers (Languages.all) sit under the mic; changes apply next window.
 */
class MicTranslatorActivity : AppCompatActivity() {

    private lateinit var audioCapture: AudioCapture
    private lateinit var captionScroll: ScrollView
    private lateinit var captionText: TextView
    private lateinit var btnMic: MaterialButton
    private lateinit var pickerFrom: NumberPicker
    private lateinit var pickerTo: NumberPicker
    private var refractBackground: RefractBackgroundView? = null

    private var engine: InferenceEngine? = null
    private var translation: MlKitTranslationEngine? = null
    private var asrJob: Job? = null
    private var micOn = false
    private var engineReady = false
    private var lastPublishedNorm = ""
    private var lastEmitAtMs = 0L
    private val lines = ArrayList<String>()

    /** Volatile so ASR loop reads picker changes without locks. */
    @Volatile private var fromCode: String = "en"
    @Volatile private var toCode: String = "en"

    private val langCodes: List<String> = Languages.all.map { it.code }
    private val langLabels: Array<String> = Languages.all.map { it.label }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.ca_refract_charcoal)
        setContentView(R.layout.activity_mic_translator)

        audioCapture = AudioCapture(this)
        refractBackground = findViewById(R.id.refractBackground)
        refractBackground?.setAnimating(true)
        captionScroll = findViewById(R.id.captionScroll)
        captionText = findViewById(R.id.captionText)
        btnMic = findViewById(R.id.btnMic)
        pickerFrom = findViewById(R.id.pickerFrom)
        pickerTo = findViewById(R.id.pickerTo)

        loadPersistedLanguages()
        setupLanguagePickers()

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            stopMicCapture()
            finish()
        }
        btnMic.setOnClickListener { toggleMic() }
        updateMicChrome()
        renderCaptionUi()

        lifecycleScope.launch { prepareEngines() }
    }

    override fun onResume() {
        super.onResume()
        refractBackground?.setAnimating(true)
    }

    override fun onPause() {
        stopMicCapture()
        refractBackground?.setAnimating(false)
        super.onPause()
    }

    override fun onDestroy() {
        stopMicCapture()
        engine?.release()
        engine = null
        translation?.release()
        translation = null
        super.onDestroy()
    }

    private fun app(): CaptionActionApp = application as CaptionActionApp

    private fun micPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadPersistedLanguages() {
        val prefs = micPrefs()
        fromCode = prefs.getString(KEY_FROM, "en")?.takeIf { it in langCodes } ?: "en"
        val savedTo = prefs.getString(KEY_TO, null)?.takeIf { it in langCodes }
        toCode = savedTo ?: "en"
        // If To was never saved, prepareEngines defaults To to Home targetLanguage.
    }

    private fun persistLanguages() {
        micPrefs().edit()
            .putString(KEY_FROM, fromCode)
            .putString(KEY_TO, toCode)
            .apply()
    }

    private fun setupLanguagePickers() {
        fun wire(picker: NumberPicker, initialCode: String, onCode: (String) -> Unit) {
            picker.minValue = 0
            picker.maxValue = langCodes.lastIndex
            picker.displayedValues = langLabels
            picker.wrapSelectorWheel = true
            picker.value = langCodes.indexOf(initialCode).coerceAtLeast(0)
            picker.setOnValueChangedListener { _, _, newVal ->
                val code = langCodes.getOrElse(newVal) { "en" }
                onCode(code)
                persistLanguages()
                maybeEnsureMtPack(code)
            }
            styleNumberPickerOpen(picker)
        }
        wire(pickerFrom, fromCode) { fromCode = it }
        wire(pickerTo, toCode) { toCode = it }
    }

    /** Apple-like open wheel: hide selection dividers when API allows. */
    private fun styleNumberPickerOpen(picker: NumberPicker) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                picker.selectionDividerHeight = 0
            }
        } catch (_: Throwable) {
        }
        try {
            for (field in NumberPicker::class.java.declaredFields) {
                when (field.name) {
                    "mSelectionDivider" -> {
                        field.isAccessible = true
                        field.set(picker, ColorDrawable(Color.TRANSPARENT))
                    }
                    "mSelectionDividerHeight" -> {
                        field.isAccessible = true
                        field.setInt(picker, 0)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        try {
            picker.setBackgroundColor(Color.TRANSPARENT)
        } catch (_: Throwable) {
        }
    }

    private fun maybeEnsureMtPack(toOrFrom: String) {
        val mt = translation ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            mt.ensureModels(targetLanguage = toCode, extraSources = setOf(fromCode, toOrFrom))
        }
    }

    private suspend fun prepareEngines() {
        val cache = ModelCache(this)
        val settings = app().settings.current()
        // Default To to Home target when prefs never set To.
        if (micPrefs().getString(KEY_TO, null) == null) {
            val home = settings.targetLanguage.takeIf { it in langCodes } ?: "en"
            toCode = home
            withContext(Dispatchers.Main) {
                if (::pickerTo.isInitialized) {
                    pickerTo.value = langCodes.indexOf(home).coerceAtLeast(0)
                }
            }
            persistLanguages()
        }
        val tier = ModelTier.BALANCED
        if (!cache.isReadyForAsr(tier)) {
            Toast.makeText(this, getString(R.string.error_model), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val preferred = InferenceEngineFactory.create(
            context = this,
            tier = tier,
            targetLanguage = toCode
        )
        if (preferred == null) {
            Toast.makeText(this, getString(R.string.error_engine_load), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        preferred.setSourceLanguage(fromCode)
        preferred.setTargetLanguage(toCode)
        preferred.setDualSubtitles(settings.dualSubtitles && preferred.canProvideDualSubtitles())
        preferred.setPlaybackCapture(false)
        val loaded = withContext(Dispatchers.IO) { preferred.load(cache.fileFor(tier)) }
        if (!loaded) {
            preferred.release()
            Toast.makeText(this, getString(R.string.error_engine_load), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        engine = preferred
        val mt = MlKitTranslationEngine(this)
        translation = mt
        lifecycleScope.launch(Dispatchers.IO) {
            val ensure = mt.ensureModels(
                targetLanguage = toCode,
                extraSources = setOf(fromCode) + settings.passthroughLanguages
            )
            if (ensure is EnsureResult.Failed) {
                Log.w(TAG, "MT packs: ${ensure.message}")
            }
        }
        engineReady = true
    }

    private fun toggleMic() {
        if (micOn) {
            stopMicCapture()
        } else {
            startMicCapture()
        }
    }

    private fun startMicCapture() {
        if (!engineReady || engine == null) {
            Toast.makeText(this, getString(R.string.loading_engine), Toast.LENGTH_SHORT).show()
            return
        }
        if (!audioCapture.hasRecordAudioPermission()) {
            Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
            return
        }
        if (!audioCapture.startMicrophoneCapture()) {
            Toast.makeText(this, getString(R.string.error_mic_capture), Toast.LENGTH_LONG).show()
            return
        }
        micOn = true
        updateMicChrome()
        renderCaptionUi()
        audioCapture.startPump(lifecycleScope)
        asrJob?.cancel()
        asrJob = lifecycleScope.launch(Dispatchers.IO) {
            runAsrLoop()
        }
    }

    private fun stopMicCapture() {
        micOn = false
        asrJob?.cancel()
        asrJob = null
        audioCapture.stop()
        updateMicChrome()
        renderCaptionUi()
    }

    private fun updateMicChrome() {
        if (!::btnMic.isInitialized) return
        btnMic.alpha = if (micOn) 1f else 0.85f
        btnMic.contentDescription = getString(
            if (micOn) R.string.mic_translator_mic_on else R.string.mic_translator_mic_off
        )
        btnMic.text = if (micOn) "⏹" else "🎙"
    }

    /**
     * FIFO mic streaming: ~1.5–2.0 s windows via [AudioCapture.drainFifoWindow] with
     * forceFlush=true so each window runs ASR immediately (completeness > tiny hops).
     * Never skips windows when ASR is slow — serialize FIFO; pump stays running.
     * From/To read each window (volatile). Whisper: language=From, translate=false.
     * Skip MT when from==to (ASR only).
     */
    private suspend fun runAsrLoop() {
        val active = engine ?: return
        val mt = translation
        while (coroutineContext.isActive && micOn) {
            val drainStart = System.currentTimeMillis()
            // FIFO oldest-first ~1.75 s @ 16 kHz — do not use drainToNewestWindow (drops speech).
            val pcm = audioCapture.drainFifoWindow(WINDOW_SAMPLES) ?: break
            val drainMs = System.currentTimeMillis() - drainStart
            if (!micOn) break

            val source = fromCode
            val target = toCode
            active.setSourceLanguage(source)
            active.setTargetLanguage(target)
            val settings = app().settings.current()
            val dual = active.canProvideDualSubtitles() && settings.dualSubtitles
            active.setDualSubtitles(dual)
            active.setPlaybackCapture(false)

            val asrStart = System.currentTimeMillis()
            val raw = active.transcribeWindow(pcm, AudioCapture.SAMPLE_RATE, forceFlush = true)
            val asrMs = System.currentTimeMillis() - asrStart
            val totalMs = System.currentTimeMillis() - drainStart
            if (raw == null) {
                Log.i(TAG, "emit skip null drainMs=$drainMs asrMs=$asrMs totalMs=$totalMs samples=${pcm.size}")
                continue
            }
            val primaryRaw = raw.text.trim()
            if (primaryRaw.isEmpty()) {
                Log.i(TAG, "emit skip empty drainMs=$drainMs asrMs=$asrMs totalMs=$totalMs")
                continue
            }
            val norm = AsrJunkFilter.normalize(primaryRaw)
            if (norm.isEmpty()) continue
            if (norm == lastPublishedNorm) {
                Log.i(TAG, "emit skip dup drainMs=$drainMs asrMs=$asrMs totalMs=$totalMs")
                continue
            }

            Log.i(
                TAG,
                "emit drainMs=$drainMs asrMs=$asrMs totalMs=$totalMs samples=${pcm.size} " +
                    "from=$source to=$target mode=${active.lastAsrMode()} text=${primaryRaw.take(48)}"
            )

            val now = System.currentTimeMillis()
            val silenceGap = lastEmitAtMs > 0L && (now - lastEmitAtMs) >= UTTERANCE_GAP_MS
            val reviseSameUtterance = !silenceGap &&
                lastPublishedNorm.isNotEmpty() &&
                lines.isNotEmpty() &&
                isSameUtteranceRevision(lastPublishedNorm, norm)
            lastPublishedNorm = norm
            lastEmitAtMs = now

            val fromNorm = MlKitTranslationEngine.normalizeLangStatic(source)
            val toNorm = MlKitTranslationEngine.normalizeLangStatic(target)
            // Same language → ASR only (no MT). Also skip when already tagged as target.
            val skipMt = fromNorm == toNorm

            if (skipMt) {
                val line = formatDisplayLine(raw, dual, target)
                if (line.isBlank()) continue
                withContext(Dispatchers.Main) {
                    publishCaptionLine(line, replaceLast = reviseSameUtterance)
                }
                continue
            }

            // Publish ASR immediately, then revise in place when MT returns.
            val asrTagged = if (raw.language.isBlank() || raw.language == "auto") {
                raw.copy(language = source)
            } else {
                raw
            }
            val asrLine = formatDisplayLine(asrTagged, dual, target)
            if (asrLine.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    publishCaptionLine(asrLine, replaceLast = reviseSameUtterance)
                }
            }
            val settingsForMt = settings.copy(targetLanguage = target)
            val policy = try {
                mt?.applyPolicy(asrTagged, settingsForMt) ?: asrTagged
            } catch (t: Throwable) {
                Log.w(TAG, "applyPolicy: ${t.message}")
                asrTagged
            }
            val line = formatDisplayLine(policy, dual, target)
            if (line.isBlank() || line == asrLine) continue
            withContext(Dispatchers.Main) {
                publishCaptionLine(line, replaceLast = true)
            }
        }
    }

    private fun isSameUtteranceRevision(prevNorm: String, nextNorm: String): Boolean {
        if (prevNorm.isEmpty() || nextNorm.isEmpty()) return false
        if (AsrJunkFilter.isNearDuplicate(prevNorm, nextNorm)) return true
        if (nextNorm.startsWith(prevNorm) || prevNorm.startsWith(nextNorm)) return true
        val common = commonPrefixLength(prevNorm, nextNorm)
        if (common >= 8) {
            val lenDelta = kotlin.math.abs(nextNorm.length - prevNorm.length)
            if (lenDelta <= 48) return true
        }
        val prevTok = prevNorm.split(' ').filter { it.isNotEmpty() }
        val nextTok = nextNorm.split(' ').filter { it.isNotEmpty() }
        if (prevTok.isNotEmpty() && nextTok.isNotEmpty()) {
            val nextSet = nextTok.toSet()
            val overlap = prevTok.count { it in nextSet }
            val smaller = minOf(prevTok.size, nextTok.size)
            if (overlap * 2 >= smaller && kotlin.math.abs(prevTok.size - nextTok.size) <= 4) {
                return true
            }
        }
        return false
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun formatDisplayLine(
        result: CaptionResult,
        dual: Boolean,
        targetLanguage: String
    ): String {
        val translated = result.translatedText?.trim().orEmpty()
        val source = result.text.trim()
        val target = MlKitTranslationEngine.normalizeLangStatic(targetLanguage)
        val detected = MlKitTranslationEngine.normalizeLangStatic(result.language)
        val showTranslated = translated.isNotEmpty() &&
            !translated.equals(source, ignoreCase = false) &&
            !(detected.isNotEmpty() && detected == target)
        return when {
            dual && showTranslated && source.isNotEmpty() -> "$translated\n$source"
            showTranslated -> translated
            else -> source
        }
    }

    private fun publishCaptionLine(line: String, replaceLast: Boolean) {
        if (replaceLast && lines.isNotEmpty()) {
            lines[lines.lastIndex] = line
        } else {
            lines.add(line)
        }
        while (lines.size > 80) lines.removeAt(0)
        renderCaptionUi()
    }

    /** Empty hint only when mic off and no lines; listening hint while mic on with no lines. */
    private fun renderCaptionUi() {
        if (!::captionText.isInitialized) return
        captionText.text = when {
            lines.isNotEmpty() -> lines.joinToString("\n\n")
            micOn -> getString(R.string.mic_translator_listening)
            else -> getString(R.string.mic_translator_empty)
        }
        if (lines.isNotEmpty() || micOn) {
            captionScroll.post {
                captionScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val TAG = "MicTranslator"
        private const val PREFS_NAME = "mic_translator"
        private const val KEY_FROM = "mic_from"
        private const val KEY_TO = "mic_to"
        /** ~1.75 s @ 16 kHz (within 24_000–32_000) — completeness over tiny hops. */
        private const val WINDOW_SAMPLES = 28_000
        /** Treat a long quiet gap as a new utterance (append, don't revise). */
        private const val UTTERANCE_GAP_MS = 1_500L
    }
}
