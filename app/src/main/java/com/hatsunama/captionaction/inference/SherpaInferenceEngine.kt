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

    // MT / dual owned by MlKitTranslationEngine; ASR stays ASR-only here.
    override fun offlineTranslationTargets(): Set<String> =
        com.hatsunama.captionaction.util.Languages.all.map { it.code }.toSet()

    override fun canProvideDualSubtitles(): Boolean = true

    override fun setPlaybackCapture(enabled: Boolean) {
        playbackCapture = enabled
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

            lastMode = "auto-translate"
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
                val endMs = System.currentTimeMillis()
                CaptionResult(
                    text = text,
                    language = result.lang.ifBlank { "auto" },
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
    }

    private fun ensureTokensBeside(modelFile: File): File {
        val dest = File(modelFile.parentFile, "tokens.txt")
        if (dest.exists() && dest.length() > 1_000L) return dest
        appContext.assets.open("sherpa/sensevoice-tokens.txt").use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
        return dest
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
        const val LIVE_WINDOW_SAMPLES = 16_000
        private const val TAG = "SherpaInferenceEngine"
        private const val MIN_SAMPLES_MIC = 12_000  // match live drain window
        private const val MIN_SAMPLES_PLAYBACK = 12_000  // live ~1.0s windows always clear primary gate
        private const val FLUSH_AT_SPEECH = 16_000 * 5
        private const val FLUSH_AT_PLAYBACK = 16_000 * 4
        private const val MAX_SAMPLES = 16_000 * 8
        private const val MIC_SPEECH_RMS = 80f
        private const val MIC_DISCARD_RMS = 80f
        private const val PLAYBACK_SPEECH_RMS = 4f
        private const val PLAYBACK_DISCARD_RMS = 1f
    }
}
