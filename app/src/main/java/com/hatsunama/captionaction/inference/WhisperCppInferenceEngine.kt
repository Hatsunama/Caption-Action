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
 * Live continuous + Quality ASR via whisper.cpp (ffmpegkit AAR).
 * Live default = Tiny (Balanced), language=auto for all Languages.all when sourceLanguage empty.
 *
 * Windows: ~1.5 s normally; ~1.125 s when [setKeepUpBehind] so mid-device passes finish nearer
 * realtime (still ≥1000 ms native min). Short PCM is silence-padded.
 *
 * Language:
 * - [setSourceLanguage] non-empty (Home Input / Mic From): `language=<code>, translate=false`
 *   (`mic-source-*`); bypasses en-direct / auto bias. Live always sets Home inputLanguage.
 * - Empty source (legacy): Live EN target + !dual → en-direct bias / auto-translate-en;
 *   dual / non-EN → auto + ML Kit. Prefer callers set an explicit source.
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
    /** Empty = auto (legacy). Non-empty = explicit input / From language. */
    @Volatile private var sourceLanguage: String = ""
    @Volatile private var lastMode: String = ""
    @Volatile private var keepUpBehind: Boolean = false
    /** ≥0 → prefer en-direct for EN target; negative → auto+translate (multilingual). */
    @Volatile private var enDirectBias: Int = 1

    override fun offlineTranslationTargets(): Set<String> =
        com.hatsunama.captionaction.util.Languages.all.map { it.code }.toSet()

    override fun canProvideDualSubtitles(): Boolean = true

    override fun setTargetLanguage(code: String) {
        targetLanguage = code
    }

    override fun setSourceLanguage(code: String) {
        sourceLanguage = code.trim().lowercase()
    }

    override fun setDualSubtitles(enabled: Boolean) {
        dualSubtitles = enabled
    }

    override fun setPlaybackCapture(enabled: Boolean) {
        playbackCapture = enabled
    }

    override fun lastAsrMode(): String = lastMode

    override fun setKeepUpBehind(behind: Boolean) {
        keepUpBehind = behind
    }

    override fun preferredWindowSamples(): Int =
        if (keepUpBehind) QUALITY_WINDOW_BEHIND_SAMPLES else QUALITY_WINDOW_SAMPLES

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
        // Mic-only shorter flush; Live playback keeps FLUSH_AT_PLAYBACK (unchanged).
        val flushAt = if (playbackCapture) FLUSH_AT_PLAYBACK else FLUSH_AT_MIC

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
                    // Mic path: snappier silence-end (~280 ms) + shorter FLUSH_AT_MIC.
                    // Playback branch above is intentionally separate and unchanged.
                    when {
                        n >= MAX_SAMPLES -> true
                        n >= minSamples && !speaking && (now - lastSpeechAt) > MIC_SILENCE_END_MS -> true
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

            val explicitSource = normalizeLang(sourceLanguage)
            val enTargetSingle = target == "en" && !dualSubtitles
            val useEnDirect = enTargetSingle && enDirectBias >= 0
            val language: String
            val translate: Boolean
            when {
                // Home Input / Mic From (or any caller that set source): explicit lang for ALL
                // Languages.all codes (zh/ja/ko/ar/…), never language=auto, never whisper-translate.
                // lastMode e.g. mic-source-zh (Live Input uses same mode string).
                explicitSource.isNotEmpty() -> {
                    language = explicitSource
                    translate = false
                    lastMode = "mic-source-$explicitSource"
                }
                useEnDirect -> {
                    language = "en"
                    translate = false
                    lastMode = "en-direct"
                }
                enTargetSingle -> {
                    language = "auto"
                    translate = true
                    lastMode = "auto-translate-en"
                }
                else -> {
                    language = "auto"
                    translate = false
                    lastMode = "auto-mlkit"
                }
            }
            val text = runWhisper(
                m,
                wav,
                language = language,
                translate = translate,
                pcmMs = pcm.size * 1000L / sampleRateHz,
                wavBytes = wav.length(),
                windowRms = windowRms
            )
            if (text == null) {
                // Speech-ish window but no usable text → nudge away from en-direct.
                if (enTargetSingle && windowRms >= discardGate * 2f) {
                    enDirectBias = (enDirectBias - 1).coerceAtLeast(-3)
                }
                return@withContext null
            }
            if (enTargetSingle) {
                enDirectBias = if (looksMostlyLatin(text)) {
                    (enDirectBias + 1).coerceAtMost(3)
                } else {
                    (enDirectBias - 2).coerceAtLeast(-3)
                }
            }

            CaptionResult(
                text = text,
                language = when {
                    explicitSource.isNotEmpty() -> explicitSource
                    useEnDirect || (enTargetSingle && translate) -> "en"
                    else -> "auto"
                },
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
        // Mic: fewer threads (2–4) to avoid thrash; playback keeps 6.
        val threadCount = if (playbackCapture) {
            6
        } else {
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        }
        val config = WhisperConfig(
            language = language,
            translate = translate,
            threads = threadCount,
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
        keepUpBehind = false
        enDirectBias = 1
        sourceLanguage = ""
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

    private fun looksMostlyLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val latin = letters.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latin * 100 / letters.length >= 70
    }

    private fun normalizeLang(code: String): String {
        val c = code.trim().lowercase()
        if (c.isEmpty() || c == "auto" || c == "unknown") return ""
        return c.removePrefix("<|").removeSuffix("|>").substringBefore('-').substringBefore('_')
    }

    companion object {
        private const val TAG = "WhisperCppEngine"
        /** Quality drain target ~1.5 s @ 16 kHz — shorter passes keep mid-devices updating. */
        const val QUALITY_WINDOW_SAMPLES = 24_000
        /** When behind (overruns/backlog): ~1.125 s — still ≥ native 1000 ms. */
        const val QUALITY_WINDOW_BEHIND_SAMPLES = 18_000
        /** Native whisper.cpp rejects audio under 1000 ms. */
        private const val NATIVE_MIN_SAMPLES = 16_000
        private const val MIN_SAMPLES_MIC = 16_000
        private const val MIN_SAMPLES_PLAYBACK = 16_000
        /** Mic continuous flush ~1.5 s @ 16 kHz (was FLUSH_AT_SPEECH = 5 s). Live unchanged. */
        private const val FLUSH_AT_MIC = 24_000
        /** Live MediaProjection path — locked; do not tighten with mic latency work. */
        private const val FLUSH_AT_PLAYBACK = 16_000 * 4
        private const val MAX_SAMPLES = 16_000 * 8
        /** Mic-only: speech-end quiet gap before flush (was 400 ms). */
        private const val MIC_SILENCE_END_MS = 280L
        private const val MIC_SPEECH_RMS = 80f
        /** Mic-only: slightly lower so quiet speech is not discarded (Live PLAYBACK_* untouched). */
        private const val MIC_DISCARD_RMS = 45f
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
