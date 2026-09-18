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

/**
 * On-device ASR via sherpa-onnx OfflineRecognizer + SenseVoice (int8).
 * Expects [modelFile] to be SenseVoice `model.int8.onnx`; tokens come from
 * assets (`sherpa/sensevoice-tokens.txt`) copied beside the model on first load.
 */
class SherpaInferenceEngine(
    private val appContext: Context
) : InferenceEngine {
    override val name: String = "Sherpa-ONNX SenseVoice"

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()

    override suspend fun load(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        release()
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
            Log.w(TAG, "SenseVoice model missing or too small: ${modelFile.absolutePath}")
            return@withContext false
        }
        // ggml whisper.cpp bins are not usable by sherpa
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
            // null AssetManager → newFromFile (filesystem paths)
            val rec = OfflineRecognizer(assetManager = null, config = config)
            synchronized(lock) { recognizer = rec }
            Log.i(TAG, "Loaded SenseVoice from ${modelFile.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create OfflineRecognizer", t)
            release()
            false
        }
    }

    override suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult? =
        withContext(Dispatchers.Default) {
            val rec = synchronized(lock) { recognizer } ?: return@withContext null
            if (pcm16le.isEmpty()) return@withContext null
            // Skip near-silence to save CPU
            if (rms(pcm16le) < 80f) return@withContext null

            val floats = FloatArray(pcm16le.size) { i -> pcm16le[i] / 32768.0f }
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
                val text = result.text.trim()
                if (text.isEmpty()) return@withContext null
                val now = System.currentTimeMillis()
                CaptionResult(
                    text = text,
                    language = result.lang.ifBlank { "auto" },
                    confidence = 0.85f,
                    startMs = now,
                    endMs = now + 1_500L,
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

    override fun release() {
        synchronized(lock) {
            try {
                recognizer?.release()
            } catch (_: Throwable) {
            }
            recognizer = null
        }
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
        private const val TAG = "SherpaInferenceEngine"
    }
}
