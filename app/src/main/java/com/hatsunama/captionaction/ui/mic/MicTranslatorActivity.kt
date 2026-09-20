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
 *
 * Mic path uses continuous streaming: ~0.5 s hops into Whisper with
 * [forceFlush]=false so the engine mic accumulator / speech-end flush runs
 * while the capture pump stays open the whole time [micOn].
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
    private var lastEmitAtMs = 0L
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
     * Continuous mic streaming: short hops (~0.5 s) with forceFlush=false so Whisper's
     * mic accumulator / speech-end path emits mid-session; pump never stops between ASR calls.
     * EN target (or Latin ASR + EN target): skip ML Kit — show raw ASR immediately.
     */
    private suspend fun runAsrLoop() {
        val active = engine ?: return
        val mt = translation
        while (coroutineContext.isActive && micOn) {
            // Hop ~0.5 s @ 16 kHz — keep drain moving; do not wait for a full Live window.
            val pcm = audioCapture.drainToNewestWindow(HOP_SAMPLES) ?: break
            if (!micOn) break
            val settings = app().settings.current()
            active.setTargetLanguage(settings.targetLanguage)
            val dual = active.canProvideDualSubtitles() && settings.dualSubtitles
            active.setDualSubtitles(dual)
            active.setPlaybackCapture(false)

            // forceFlush=false → engine accumulates and flushes on speech-end / FLUSH_AT_MIC
            // (~1.5 s continuous). Do not forceFlush with tiny hops (clears accum + null under
            // NATIVE_MIN_SAMPLES). Live path separately uses forceFlush=true + playbackCapture.
            val raw = active.transcribeWindow(pcm, AudioCapture.SAMPLE_RATE, forceFlush = false)
                ?: continue
            val primaryRaw = raw.text.trim()
            if (primaryRaw.isEmpty()) continue
            val norm = AsrJunkFilter.normalize(primaryRaw)
            if (norm.isEmpty()) continue
            // Exact-norm only: allow near-dup / growing EN phrase to revise in place.
            if (norm == lastPublishedNorm) continue

            val now = System.currentTimeMillis()
            val silenceGap = lastEmitAtMs > 0L && (now - lastEmitAtMs) >= UTTERANCE_GAP_MS
            val reviseSameUtterance = !silenceGap &&
                lastPublishedNorm.isNotEmpty() &&
                lines.isNotEmpty() &&
                isSameUtteranceRevision(lastPublishedNorm, norm)
            lastPublishedNorm = norm
            lastEmitAtMs = now

            val targetNorm = MlKitTranslationEngine.normalizeLangStatic(settings.targetLanguage)
            val latinAsr = AsrJunkFilter.scriptFamilyOf(primaryRaw) == AsrJunkFilter.ScriptFamily.LATIN
            // Mic EN fast path: skip MT dispatcher hop entirely (EN→EN / Latin→EN).
            val skipMt = targetNorm == "en" || (latinAsr && targetNorm == "en")

            if (skipMt) {
                val line = formatDisplayLine(raw, dual, settings.targetLanguage)
                if (line.isBlank()) continue
                withContext(Dispatchers.Main) {
                    publishCaptionLine(line, replaceLast = reviseSameUtterance)
                }
                continue
            }

            // Non-EN: publish ASR immediately, then revise in place when MT returns.
            val asrLine = formatDisplayLine(raw, dual, settings.targetLanguage)
            if (asrLine.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    publishCaptionLine(asrLine, replaceLast = reviseSameUtterance)
                }
            }
            val policy = try {
                mt?.applyPolicy(raw, settings) ?: raw
            } catch (t: Throwable) {
                Log.w(TAG, "applyPolicy: ${t.message}")
                raw
            }
            val line = formatDisplayLine(policy, dual, settings.targetLanguage)
            if (line.isBlank() || line == asrLine) continue
            withContext(Dispatchers.Main) {
                publishCaptionLine(line, replaceLast = true)
            }
        }
    }

    private fun isSameUtteranceRevision(prevNorm: String, nextNorm: String): Boolean {
        if (prevNorm.isEmpty() || nextNorm.isEmpty()) return false
        if (AsrJunkFilter.isNearDuplicate(prevNorm, nextNorm)) return true
        // Growing / shrinking partials for the same spoken phrase.
        if (nextNorm.startsWith(prevNorm) || prevNorm.startsWith(nextNorm)) return true
        // Looser mic-side revise: shared prefix so growing EN phrases update in place.
        val common = commonPrefixLength(prevNorm, nextNorm)
        if (common >= 8) {
            val lenDelta = kotlin.math.abs(nextNorm.length - prevNorm.length)
            if (lenDelta <= 48) return true
        }
        // Token overlap — same utterance with mid-phrase ASR drift.
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
            !(detected.isNotEmpty() && detected == target && target == "en")
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
        /** ~0.5 s @ 16 kHz — snappier continuous feel (was 12_000 / 0.75 s). */
        private const val HOP_SAMPLES = 8_000
        /** Treat a long quiet gap as a new utterance (append, don't revise). */
        private const val UTTERANCE_GAP_MS = 1_500L
    }
}
