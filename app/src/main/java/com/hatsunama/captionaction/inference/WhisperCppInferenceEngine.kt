package com.hatsunama.captionaction.inference

import android.content.Context
import android.util.Log
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Quality-path ASR via whisper.cpp (ffmpegkit AAR).
 *
 * Multilingual by default: language=auto for every source. translate=true only when the
 * subtitle target is English (whisper's built-in translate-to-EN). Dual / non-EN targets keep
 * auto + translate=false and rely on ML Kit async MT — no brittle en-direct fast path.
 *
 * whisper.cpp rejects audio shorter than 1000 ms (`input is too short`); Quality windows are
 * therefore ~2.5 s and short PCM is silence-padded before native transcribe.
 */
class WhisperCppInferenceEngine(
    private val appContext: Context,
    @Volatile private var targetLanguage: String = "en"
) : InferenceEngine {
    override val name: String = "whisper.cpp"

    private val lock = Any()
    private var model: WhisperModel? = null
    private val pcmAccum = ArrayList<Short>(16_000 * 4)
    private var lastSpeechAt = 0L
    @Volatile private var dualSubtitles: Boolean = false
    @Volatile private var playbackCapture: Boolean = false
    @Volatile private var lastMode: String = ""

    override fun offlineTranslationTargets(): Set<String> =
        com.hatsunama.captionaction.util.Languages.all.map { it.code }.toSet()

    override fun canProvideDualSubtitles(): Boolean = true

    override fun setTargetLanguage(code: String) {
        targetLanguage = code
    }

    override fun setDualSubtitles(enabled: Boolean) {
        dualSubtitles = enabled
    }

    override fun setPlaybackCapture(enabled: Boolean) {
        playbackCapture = enabled
    }

    override fun lastAsrMode(): String = lastMode

    override fun preferredWindowSamples(): Int = QUALITY_WINDOW_SAMPLES

    override suspend fun load(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        release()
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
            Log.w(TAG, "ggml model missing or too small: ${modelFile.absolutePath}")
            return@withContext false
        }
        val nameLower = modelFile.name.lowercase()
        if (!nameLower.endsWith(".bin") && !nameLower.contains("ggml")) {
            Log.w(TAG, "Expected ggml .bin, got ${modelFile.name}")
            return@withContext false
        }
        return@withContext try {
            val loaded = Whisper.loadModel(appContext, modelFile.absolutePath)
            synchronized(lock) {
                model = loaded
                pcmAccum.clear()
            }
            Log.i(TAG, "Loaded whisper ggml from ${modelFile.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load whisper model", t)
            release()
            false
        }
    }

    override suspend fun transcribeWindow(
        pcm16le: ShortArray,
        sampleRateHz: Int,
        forceFlush: Boolean
    ): CaptionResult? = withContext(Dispatchers.Default) {
        val m = synchronized(lock) { model }
        if (m == null) {
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
                pcm16le.size >= NATIVE_MIN_SAMPLES -> pcm16le
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
                } else if (pcmAccum.size < NATIVE_MIN_SAMPLES) {
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
        val windowRms = rms(pcm)
        val discardGate = if (playbackCapture) PLAYBACK_DISCARD_RMS else MIC_DISCARD_RMS
        if (windowRms < discardGate) {
            lastMode = ""
            return@withContext null
        }

        // whisper.cpp: "input is too short - %d ms < 1000 ms" — pad so native actually runs.
        val pcmForWhisper = ensureMinDuration(pcm, sampleRateHz, NATIVE_MIN_SAMPLES)

        val wav = writeTempWav(pcmForWhisper, sampleRateHz)
        try {
            val target = normalizeLang(targetLanguage)
            val endMs = System.currentTimeMillis()
            val startMs = endMs - (pcm.size * 1000L / sampleRateHz)

            // Systemic multilingual path (any source → target):
            // - Always language=auto (never force en-direct; that breaks CN/JA/… sources).
            // - translate=true only for single-line EN target (whisper translate-to-EN).
            // - Dual / non-EN: auto + translate=false; ML Kit fills translatedText async.
            val translateToEn = target == "en" && !dualSubtitles
            lastMode = if (translateToEn) "auto-translate-en" else "auto-mlkit"
            val text = runWhisper(
                m,
                wav,
                language = "auto",
                translate = translateToEn,
                pcmMs = pcm.size * 1000L / sampleRateHz,
                wavBytes = wav.length(),
                windowRms = windowRms
            ) ?: return@withContext null

            CaptionResult(
                text = text,
                language = if (translateToEn) "en" else "auto",
                confidence = 0.8f,
                startMs = startMs,
                endMs = endMs,
                translatedText = null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "whisper transcribe failed", t)
            lastMode = ""
            null
        } finally {
            wav.delete()
        }
    }

    private suspend fun runWhisper(
        m: WhisperModel,
        wav: File,
        language: String,
        translate: Boolean,
        pcmMs: Long,
        wavBytes: Long,
        windowRms: Float
    ): String? {
        val config = WhisperConfig(
            language = language,
            translate = translate,
            threads = 6,
            maxSegmentLength = 0,
            printTimestamps = false
        )
        val result = Whisper.transcribe(m, wav.absolutePath, config)
        val raw = result.text
        val rawLen = raw.length
        Log.i(
            TAG,
            "whisper native pcmMs=$pcmMs wavBytes=$wavBytes windowRms=${"%.1f".format(windowRms)} " +
                "lang=$language translate=$translate processingTimeMs=${result.processingTimeMs} " +
                "rawLen=$rawLen rawPreview=${raw.trim().take(64).replace('\n', ' ')}"
        )
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            // Empty + tiny processingTimeMs ⇒ native early-exit (too short / failed decode), not real infer.
            return null
        }
        return AsrJunkFilter.sanitizeOrNull(trimmed)
    }

    override fun discardPendingAudio() {
        synchronized(lock) { pcmAccum.clear() }
    }

    override fun release() {
        synchronized(lock) {
            try {
                model?.let { Whisper.releaseModel(it) }
            } catch (_: Throwable) {
            }
            model = null
            pcmAccum.clear()
        }
        lastMode = ""
    }

    private fun ensureMinDuration(pcm: ShortArray, sampleRateHz: Int, minSamples: Int): ShortArray {
        if (pcm.size >= minSamples) return pcm
        val out = ShortArray(minSamples)
        System.arraycopy(pcm, 0, out, 0, pcm.size)
        Log.i(
            TAG,
            "padded pcm ${pcm.size}→$minSamples samples (~${pcm.size * 1000 / sampleRateHz}→" +
                "${minSamples * 1000 / sampleRateHz} ms) for whisper min duration"
        )
        return out
    }

    private fun writeTempWav(pcm: ShortArray, sampleRateHz: Int): File {
        val file = File(appContext.cacheDir, "whisper_chunk_${UUID.randomUUID()}.wav")
        val dataBytes = pcm.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeAscii("RIFF")
            raf.writeIntLE(36 + dataBytes)
            raf.writeAscii("WAVE")
            raf.writeAscii("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1) // PCM
            raf.writeShortLE(1) // mono
            raf.writeIntLE(sampleRateHz)
            raf.writeIntLE(sampleRateHz * 2)
            raf.writeShortLE(2)
            raf.writeShortLE(16)
            raf.writeAscii("data")
            raf.writeIntLE(dataBytes)
            val buf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) buf.putShort(s)
            raf.write(buf.array())
        }
        return file
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(
            byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte()
            )
        )
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
    }

    private fun RandomAccessFile.writeAscii(s: String) {
        write(s.toByteArray(Charsets.US_ASCII))
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

    private fun normalizeLang(code: String): String {
        val c = code.trim().lowercase()
        if (c.isEmpty() || c == "auto" || c == "unknown") return ""
        return c.removePrefix("<|").removeSuffix("|>").substringBefore('-').substringBefore('_')
    }

    companion object {
        private const val TAG = "WhisperCppEngine"
        /** Quality drain target ~2.5 s @ 16 kHz — enough for whisper.cpp to emit text. */
        const val QUALITY_WINDOW_SAMPLES = 40_000
        /** Native whisper.cpp rejects audio under 1000 ms. */
        private const val NATIVE_MIN_SAMPLES = 16_000
        private const val MIN_SAMPLES_MIC = 32_000
        private const val MIN_SAMPLES_PLAYBACK = 32_000
        private const val FLUSH_AT_SPEECH = 16_000 * 5
        private const val FLUSH_AT_PLAYBACK = 16_000 * 4
        private const val MAX_SAMPLES = 16_000 * 8
        private const val MIC_SPEECH_RMS = 80f
        private const val MIC_DISCARD_RMS = 60f
        private const val PLAYBACK_SPEECH_RMS = 4f
        private const val PLAYBACK_DISCARD_RMS = 1f

        fun isNativeAvailable(): Boolean {
            return try {
                Whisper.getSystemInfo()
                true
            } catch (t: Throwable) {
                Log.w(TAG, "whisper native not loadable: ${t.message}")
                false
            }
        }
    }
}
