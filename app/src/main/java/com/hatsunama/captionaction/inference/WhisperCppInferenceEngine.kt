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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            val m = synchronized(lock) { model } ?: return@withContext null
            if (pcm16le.isEmpty()) return@withContext null

            val now = System.currentTimeMillis()
            val energy = rms(pcm16le)
            val speechGate = if (playbackCapture) PLAYBACK_SPEECH_RMS else MIC_SPEECH_RMS
            val speaking = energy >= speechGate
            if (speaking) lastSpeechAt = now

            val minSamples = if (playbackCapture) MIN_SAMPLES_PLAYBACK else MIN_SAMPLES_MIC
            val flushAt = if (playbackCapture) FLUSH_AT_PLAYBACK else FLUSH_AT_SPEECH

            val samples: ShortArray?
            if (forceFlush) {
                // Live consumer window: treat this PCM as a complete utterance — no re-accum.
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
            val pcm = samples ?: return@withContext null
            val discardGate = if (playbackCapture) PLAYBACK_DISCARD_RMS else MIC_DISCARD_RMS
            if (rms(pcm) < discardGate) return@withContext null

            val wav = writeTempWav(pcm, sampleRateHz)
            try {
                val target = normalizeLang(targetLanguage)
                val endMs = System.currentTimeMillis()
                val startMs = endMs - (pcm.size * 1000L / sampleRateHz)

                // EN target (single-line): language=en, translate=false — skip auto-detect + translate tax.
                // Dual / non-EN: language=auto, translate=false; ML Kit fills translatedText async.
                if (target == "en" && !dualSubtitles) {
                    lastMode = "en-direct"
                    val text = runWhisper(m, wav, language = "en", translate = false)
                        ?: return@withContext null
                    CaptionResult(
                        text = text,
                        language = "en",
                        confidence = 0.8f,
                        startMs = startMs,
                        endMs = endMs,
                        translatedText = null
                    )
                } else {
                    lastMode = "auto-translate"
                    val text = runWhisper(m, wav, language = "auto", translate = false)
                        ?: return@withContext null
                    CaptionResult(
                        text = text,
                        language = "auto",
                        confidence = 0.8f,
                        startMs = startMs,
                        endMs = endMs,
                        translatedText = null
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "whisper transcribe failed", t)
                null
            } finally {
                wav.delete()
            }
        }

    private suspend fun runWhisper(
        m: WhisperModel,
        wav: File,
        language: String,
        translate: Boolean
    ): String? {
        val config = WhisperConfig(
            language = language,
            translate = translate,
            // Live keep-up: raise 4→6 so mid-device Accurate finishes nearer real-time.
            threads = 6,
            maxSegmentLength = 0,
            printTimestamps = false
        )
        val result = Whisper.transcribe(m, wav.absolutePath, config)
        val raw = result.text.trim().takeIf { it.isNotEmpty() } ?: return null
        return AsrJunkFilter.sanitizeOrNull(raw)
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

    private fun writeTempWav(pcm: ShortArray, sampleRateHz: Int): File {
        val file = File(appContext.cacheDir, "whisper_chunk_${Thread.currentThread().id}.wav")
        val dataBytes = pcm.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.writeAscii("RIFF")
            raf.writeIntLE(36 + dataBytes)
            raf.writeAscii("WAVE")
            raf.writeAscii("fmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1)
            raf.writeShortLE(1)
            raf.writeIntLE(sampleRateHz)
            raf.writeIntLE(sampleRateHz * 2)
            raf.writeShortLE(2)
            raf.writeShortLE(16)
            raf.writeAscii("data")
            raf.writeIntLE(dataBytes)
            val buf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
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
        /** Mic live min ~1.5 s. */
        private const val MIN_SAMPLES_MIC = 12_000  // match live drain window
        /** Playback live min ~1.25 s (was 2 s) so first caption arrives sooner. */
        private const val MIN_SAMPLES_PLAYBACK = 12_000  // live ~1.0s windows always clear primary gate
        private const val FLUSH_AT_SPEECH = 16_000 * 5
        private const val FLUSH_AT_PLAYBACK = 16_000 * 4
        /** Max keep ~8 s. */
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
