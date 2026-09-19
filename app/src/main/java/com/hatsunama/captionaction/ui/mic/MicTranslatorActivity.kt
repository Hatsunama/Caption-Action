package com.hatsunama.captionaction.ui.mic

import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * In-app microphone → whisper Tiny ASR → ML Kit translate.
 * Isolated from Live Captions (which stays MediaProjection / playback-only).
 */
class MicTranslatorActivity : AppCompatActivity() {

    private lateinit var audioCapture: AudioCapture
    private lateinit var captionScroll: ScrollView
    private lateinit var captionText: TextView
    private lateinit var btnMic: MaterialButton
    private var refractBackground: RefractBackgroundView? = null

    private var engine: InferenceEngine? = null
    private var translation: MlKitTranslationEngine? = null
    private var asrJob: Job? = null
    private var micOn = false
    private var engineReady = false
    private var lastPublishedNorm = ""
    private val lines = ArrayList<String>()

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

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            stopMicCapture()
            finish()
        }
        btnMic.setOnClickListener { toggleMic() }
        updateMicChrome()

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

    private suspend fun prepareEngines() {
        val cache = ModelCache(this)
        val settings = app().settings.current()
        val tier = ModelTier.BALANCED
        if (!cache.isReadyForAsr(tier)) {
            Toast.makeText(this, getString(R.string.error_model), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val preferred = InferenceEngineFactory.create(
            context = this,
            tier = tier,
            targetLanguage = settings.targetLanguage
        )
        if (preferred == null) {
            Toast.makeText(this, getString(R.string.error_engine_load), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        preferred.setTargetLanguage(settings.targetLanguage)
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
                targetLanguage = settings.targetLanguage,
                extraSources = settings.passthroughLanguages
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
    }

    private fun updateMicChrome() {
        if (!::btnMic.isInitialized) return
        btnMic.alpha = if (micOn) 1f else 0.85f
        btnMic.contentDescription = getString(
            if (micOn) R.string.mic_translator_mic_on else R.string.mic_translator_mic_off
        )
        btnMic.text = if (micOn) "⏹" else "🎙"
    }

    private suspend fun runAsrLoop() {
        val active = engine ?: return
        val mt = translation
        while (coroutineContext.isActive && micOn) {
            val windowSamples = active.preferredWindowSamples()
            val pcm = audioCapture.drainToNewestWindow(windowSamples) ?: break
            if (!micOn) break
            val settings = app().settings.current()
            active.setTargetLanguage(settings.targetLanguage)
            val dual = active.canProvideDualSubtitles() && settings.dualSubtitles
            active.setDualSubtitles(dual)
            active.setPlaybackCapture(false)

            val raw = active.transcribeWindow(pcm, AudioCapture.SAMPLE_RATE, forceFlush = true)
                ?: continue
            val primaryRaw = raw.text.trim()
            if (primaryRaw.isEmpty()) continue
            val norm = AsrJunkFilter.normalize(primaryRaw)
            if (norm.isEmpty() || norm == lastPublishedNorm) continue
            if (AsrJunkFilter.isNearDuplicate(norm, lastPublishedNorm)) continue
            lastPublishedNorm = norm

            val policy = try {
                mt?.applyPolicy(raw, settings) ?: raw
            } catch (t: Throwable) {
                Log.w(TAG, "applyPolicy: ${t.message}")
                raw
            }
            val line = formatDisplayLine(policy, dual, settings.targetLanguage)
            if (line.isBlank()) continue
            withContext(Dispatchers.Main) {
                appendCaptionLine(line)
            }
        }
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
            !(detected.isNotEmpty() && detected == target && target == "en")
        return when {
            dual && showTranslated && source.isNotEmpty() -> "$translated\n$source"
            showTranslated -> translated
            else -> source
        }
    }

    private fun appendCaptionLine(line: String) {
        lines.add(line)
        // Keep a reasonable scrollback so the TextView stays snappy.
        while (lines.size > 80) lines.removeAt(0)
        captionText.text = lines.joinToString("\n\n")
        captionScroll.post {
            captionScroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    companion object {
        private const val TAG = "MicTranslator"
    }
}
