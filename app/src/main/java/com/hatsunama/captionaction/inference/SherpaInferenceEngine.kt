package com.hatsunama.captionaction.inference

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SherpaInferenceEngine(
    private val appContext: Context
) : InferenceEngine {
    override val name: String = "Sherpa-ONNX SenseVoice"

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()
    private val pcmAccum = ArrayList<Short>(16_000 * 4)
    private var lastSpeechAt = 0L
    @Volatile private var playbackCapture: Boolean = false
    @Volatile private var lastMode: String = ""
    /** Subtitle target (e.g. en) — ASR stays multilingual auto; used for diagnostics. */
    @Volatile private var targetLanguage: String = "en"
    /** Rolling CJK-vs-Latin bias from recent ASR text (positive ⇒ CJK-heavy). */
    @Volatile private var scriptBias: Int = 0

    // MT / dual owned by MlKitTranslationEngine; ASR stays ASR-only here.
    override fun offlineTranslationTargets(): Set<String> =
        com.hatsunama.captionaction.util.Languages.all.map { it.code }.toSet()

    override fun canProvideDualSubtitles(): Boolean = true

    override fun setPlaybackCapture(enabled: Boolean) {
        playbackCapture = enabled
    }

    override fun setTargetLanguage(code: String) {
        targetLanguage = code.trim().lowercase().ifBlank { "en" }
    }

    override fun lastAsrMode(): String = lastMode

    override fun preferredWindowSamples(): Int = LIVE_WINDOW_SAMPLES

    override suspend fun load(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        release()
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
            Log.w(TAG, "SenseVoice model missing or too small: ${modelFile.absolutePath}")
            return@withContext false
        }
        val nameLower = modelFile.name.lowercase()
        if (nameLower.endsWith(".bin") || nameLower.startsWith("ggml-")) {
            Log.w(TAG, "ggml model present but sherpa needs ONNX SenseVoice; refusing ${modelFile.name}")
            return@withContext false
        }
        return@withContext try {
            val tokens = ensureTokensBeside(modelFile)
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = "auto",
                        useInverseTextNormalization = true
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                ),
                decodingMethod = "greedy_search"
            )
            val rec = OfflineRecognizer(assetManager = null, config = config)
            synchronized(lock) {
                recognizer = rec
                pcmAccum.clear()
            }
            Log.i(TAG, "Loaded SenseVoice from ${modelFile.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create OfflineRecognizer", t)
            release()
            false
        }
    }

    override suspend fun transcribeWindow(
        pcm16le: ShortArray,
        sampleRateHz: Int,
        forceFlush: Boolean
    ): CaptionResult? =
        withContext(Dispatchers.Default) {
            val rec = synchronized(lock) { recognizer }
            if (rec == null) {
                lastMode = ""
                return@withContext null
            }
            if (pcm16le.isEmpty()) {
                lastMode = ""
                return@withContext null
            }

            val now = System.currentTimeMillis()
            val energy = rms(pcm16le)
            val speechGate = if (playbackCapture) PLAYBACK_SPEECH_RMS else MIC_SPEECH_RMS
            val speaking = energy >= speechGate
            if (speaking) lastSpeechAt = now

            val minSamples = if (playbackCapture) MIN_SAMPLES_PLAYBACK else MIN_SAMPLES_MIC
            val flushAt = if (playbackCapture) FLUSH_AT_PLAYBACK else FLUSH_AT_SPEECH

            val samples: ShortArray?
            if (forceFlush) {
                synchronized(lock) { pcmAccum.clear() }
                samples = when {
                    pcm16le.size >= minSamples -> pcm16le
                    pcm16le.size >= minSamples / 2 -> pcm16le
                    else -> null
                }
            } else {
                val shouldFlush: Boolean
                val chunk: ShortArray?
                synchronized(lock) {
                    for (s in pcm16le) pcmAccum.add(s)
                    while (pcmAccum.size > MAX_SAMPLES) {
                        pcmAccum.removeAt(0)
                    }
                    val n = pcmAccum.size
                    shouldFlush = if (playbackCapture) {
                        // Time-window flush only — muted volume must still caption.
                        when {
                            n >= MAX_SAMPLES -> true
                            n >= minSamples -> true
                            else -> false
                        }
                    } else {
                        when {
                            n >= MAX_SAMPLES -> true
                            n >= minSamples && !speaking && (now - lastSpeechAt) > 400L -> true
                            n >= flushAt -> true
                            else -> false
                        }
                    }
                    if (!shouldFlush) {
                        chunk = null
                    } else if (pcmAccum.size < minSamples / 2) {
                        chunk = null
                    } else {
                        val arr = ShortArray(pcmAccum.size)
                        for (i in pcmAccum.indices) arr[i] = pcmAccum[i]
                        pcmAccum.clear()
                        chunk = arr
                    }
                }
                samples = chunk
            }
            val pcm = samples
            if (pcm == null) {
                lastMode = ""
                return@withContext null
            }
            val discardGate = if (playbackCapture) PLAYBACK_DISCARD_RMS else MIC_DISCARD_RMS
            if (rms(pcm) < discardGate) {
                lastMode = ""
                return@withContext null
            }

            val floats = FloatArray(pcm.size) { i -> pcm[i] / 32768.0f }
            val stream = try {
                rec.createStream()
            } catch (t: Throwable) {
                Log.e(TAG, "createStream failed", t)
                return@withContext null
            }
            try {
                stream.acceptWaveform(floats, sampleRateHz)
                rec.decode(stream)
                val result = rec.getResult(stream)
                val text = AsrJunkFilter.sanitizeOrNull(result.text) ?: return@withContext null
                val rawLang = result.lang.ifBlank { "auto" }
                val lang = enrichLangFromScript(text, rawLang)
                updateScriptBias(text)
                // auto at load; bias tag helps logs when ZH→EN (target en, CJK speech).
                lastMode = when {
                    scriptBias >= 2 && targetLanguage == "en" -> "auto-zh-bias"
                    else -> "auto-translate"
                }
                val endMs = System.currentTimeMillis()
                CaptionResult(
                    text = text,
                    language = lang,
                    confidence = 0.85f,
                    startMs = endMs - (pcm.size * 1000L / sampleRateHz),
                    endMs = endMs,
                    translatedText = null
                )
            } catch (t: Throwable) {
                Log.e(TAG, "transcribe failed", t)
                null
            } finally {
                try {
                    stream.release()
                } catch (_: Throwable) {
                }
            }
        }

    override fun discardPendingAudio() {
        synchronized(lock) { pcmAccum.clear() }
    }

    override fun release() {
        synchronized(lock) {
            try {
                recognizer?.release()
            } catch (_: Throwable) {
            }
            recognizer = null
            pcmAccum.clear()
        }
        lastMode = ""
        scriptBias = 0
    }

    private fun ensureTokensBeside(modelFile: File): File {
        val dest = File(modelFile.parentFile, "tokens.txt")
        if (dest.exists() && dest.length() > 1_000L) return dest
        appContext.assets.open("sherpa/sensevoice-tokens.txt").use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
        return dest
    }

    /**
     * When SenseVoice reports auto/blank but the text is clearly CJK/JA/KO,
     * stamp a concrete source lang so ML Kit MT does not guess wrong (ZH→EN crumbs).
     * Does not force SenseVoice decode language (stays auto at load — preserves EN/JA/KO).
     */
    private fun enrichLangFromScript(text: String, rawLang: String): String {
        val n = rawLang.trim().lowercase()
        if (n.isNotEmpty() && n != "auto" && n != "unknown" && n != "und") return n
        return guessScriptLang(text) ?: n.ifBlank { "auto" }
    }

    private fun guessScriptLang(text: String): String? {
        var han = 0
        var hiraKata = 0
        var hangul = 0
        var letters = 0
        for (ch in text) {
            when {
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN -> {
                    han++; letters++
                }
                ch.code in 0x3040..0x30FF -> {
                    hiraKata++; letters++
                }
                ch.code in 0xAC00..0xD7AF -> {
                    hangul++; letters++
                }
                ch.isLetter() -> letters++
            }
        }
        if (letters == 0) return null
        // Prefer the dominant non-Latin script when it is majority.
        return when {
            han * 2 >= letters -> "zh"
            hiraKata * 2 >= letters -> "ja"
            hangul * 2 >= letters -> "ko"
            else -> null
        }
    }

    private fun updateScriptBias(text: String) {
        val guessed = guessScriptLang(text)
        scriptBias = when (guessed) {
            "zh", "ja", "ko" -> (scriptBias + 1).coerceAtMost(5)
            else -> (scriptBias - 1).coerceAtLeast(-3)
        }
    }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        val step = (samples.size / 512).coerceAtLeast(1)
        var n = 0
        var i = 0
        while (i < samples.size) {
            val v = samples[i].toDouble()
            sum += v * v
            n++
            i += step
        }
        return kotlin.math.sqrt(sum / n.coerceAtLeast(1)).toFloat()
    }

    companion object {
        /** ~2.5 s @ 16 kHz — phrase-level SenseVoice (was 2.25 s; crumbs still dominated ZH→EN). */
        const val LIVE_WINDOW_SAMPLES = 40_000
        private const val TAG = "SherpaInferenceEngine"
        private const val MIN_SAMPLES_MIC = 32_000  // align with ~2.0–2.5 s live windows
        private const val MIN_SAMPLES_PLAYBACK = 32_000
        private const val FLUSH_AT_SPEECH = 16_000 * 5
        private const val FLUSH_AT_PLAYBACK = 16_000 * 4
        private const val MAX_SAMPLES = 16_000 * 8
        private const val MIC_SPEECH_RMS = 80f
        private const val MIC_DISCARD_RMS = 80f
        private const val PLAYBACK_SPEECH_RMS = 4f
        private const val PLAYBACK_DISCARD_RMS = 1f
    }
}
