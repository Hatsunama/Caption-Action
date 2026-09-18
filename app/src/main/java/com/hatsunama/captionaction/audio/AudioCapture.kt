package com.hatsunama.captionaction.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Continuous PCM capture. A dedicated pump reads AudioRecord into a bounded queue;
 * ASR consumers pull windows and must never block [AudioRecord.read].
 *
 * When the ASR consumer falls behind, [drainToNewestWindow] drops intermediate chunks
 * and returns a contiguous newest ~1.5–2 s window so live captions stay near real-time.
 */
class AudioCapture(private val context: Context) {

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    var usingPlaybackCapture: Boolean = false
        private set

    private val pcmQueue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)
    private val overrunCount = AtomicLong(0)
    private val chunkCount = AtomicLong(0)
    private val lastRms = AtomicReference(0f)
    private var pumpJob: Job? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun overrunCount(): Long = overrunCount.get()

    fun queueDepth(): Int = pcmQueue.size

    fun chunkCount(): Long = chunkCount.get()

    /** Most recent pump-chunk RMS (PCM16 mono). */
    fun lastRms(): Float = lastRms.get()

    fun startMic(): Boolean {
        stopPumpAndRecord()
        if (!hasMicPermission()) return false
        val sampleRate = SAMPLE_RATE
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuf <= 0) return false
        val bufBytes = captureBufferBytes(minBuf)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channel,
            encoding,
            bufBytes
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }
        ar.startRecording()
        record = ar
        usingPlaybackCapture = false
        return true
    }

    fun startPlaybackCapture(projection: MediaProjection): Boolean {
        stopPumpAndRecord()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!hasMicPermission()) return false
        return try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            val config = configBuilder.build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val ar = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(captureBufferBytes(minBuf))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return false
            }
            ar.startRecording()
            record = ar
            usingPlaybackCapture = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IllegalArgumentException) {
            tryStartPlaybackCore(projection)
        }
    }

    private fun tryStartPlaybackCore(projection: MediaProjection): Boolean {
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val ar = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(captureBufferBytes(minBuf))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                return false
            }
            ar.startRecording()
            record = ar
            usingPlaybackCapture = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Start the non-blocking capture pump. Call after [startMic] / [startPlaybackCapture]. */
    fun startPump(scope: CoroutineScope) {
        pumpJob?.cancel()
        pcmQueue.clear()
        overrunCount.set(0)
        chunkCount.set(0)
        lastRms.set(0f)
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(READ_SAMPLES)
            var lastDiagAt = 0L
            while (isActive) {
                val ar = record ?: break
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) {
                    val copy = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                    lastRms.set(rms(copy))
                    enqueueDropOldest(copy)
                    val c = chunkCount.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (now - lastDiagAt >= DIAG_INTERVAL_MS) {
                        lastDiagAt = now
                        Log.d(
                            TAG,
                            "capture chunks=$c overruns=${overrunCount.get()} " +
                                "q=${pcmQueue.size} rms=${lastRms.get()}"
                        )
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read error=$n")
                    break
                }
            }
        }
    }

    /**
     * Pull one PCM chunk for the ASR consumer. Returns null on timeout while still capturing,
     * or when the pump has stopped and the queue is empty.
     */
    suspend fun takeChunk(timeoutMs: Long = TAKE_TIMEOUT_MS): ShortArray? {
        while (coroutineContext.isActive) {
            val chunk = pcmQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (chunk != null) return chunk
            if (record == null && pcmQueue.isEmpty()) return null
            if (pumpJob?.isActive != true && pcmQueue.isEmpty()) return null
        }
        return null
    }

    /**
     * Pull audio for one ASR call. When queue depth is high or overruns are rising,
     * drops intermediate chunks and returns a contiguous newest ~[targetSamples] window
     * so the consumer skips backlog instead of drowning in old speech.
     */
    suspend fun drainToNewestWindow(targetSamples: Int = DEFAULT_WINDOW_SAMPLES): ShortArray? {
        val first = takeChunk() ?: return null
        val chunks = ArrayList<ShortArray>(QUEUE_CAPACITY)
        chunks.add(first)
        while (true) {
            val next = pcmQueue.poll() ?: break
            chunks.add(next)
        }

        val depth = chunks.size
        val behind = depth >= BACKLOG_CHUNK_THRESHOLD
        if (behind) {
            Log.i(
                TAG,
                "drainToNewest: behind depth=$depth overruns=${overrunCount.get()} " +
                    "→ keep newest ~${targetSamples} samples"
            )
        }

        var total = 0
        for (c in chunks) total += c.size

        // Keep contiguous newest window (always contiguous from the end of drained chunks).
        val keepFrom: Int
        if (behind || total > targetSamples) {
            var need = targetSamples
            var i = chunks.size - 1
            while (i > 0 && need > 0) {
                need -= chunks[i].size
                if (need > 0) i--
            }
            keepFrom = if (need <= 0) i else 0
        } else {
            keepFrom = 0
        }

        var outLen = 0
        for (i in keepFrom until chunks.size) outLen += chunks[i].size
        val out = ShortArray(outLen)
        var pos = 0
        for (i in keepFrom until chunks.size) {
            val c = chunks[i]
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        // If the oldest kept chunk is larger than remaining budget, trim from the front.
        if (out.size > targetSamples && behind) {
            val start = out.size - targetSamples
            return out.copyOfRange(start, out.size)
        }
        return out
    }

    fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        stopPumpAndRecord()
        pcmQueue.clear()
    }

    private fun stopPumpAndRecord() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
        usingPlaybackCapture = false
    }

    private fun enqueueDropOldest(chunk: ShortArray) {
        if (pcmQueue.offer(chunk)) return
        pcmQueue.poll()
        overrunCount.incrementAndGet()
        if (!pcmQueue.offer(chunk)) {
            overrunCount.incrementAndGet()
        }
    }

    private fun captureBufferBytes(minBuf: Int): Int {
        // Aim ~3 s of PCM16 mono @ 16 kHz (~96 KB), never below minBuf*4.
        val target = SAMPLE_RATE * BYTES_PER_SAMPLE * CAPTURE_SECONDS
        return maxOf(minBuf * 4, target)
    }

    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        val step = (samples.size / 256).coerceAtLeast(1)
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
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CAPTURE_SECONDS = 3
        /** ~0.5 s read slices. */
        private const val READ_SAMPLES = 8_000
        /**
         * Bounded queue ≈ 10–12 s of 0.5 s chunks (was 8 / ~4 s).
         * Larger buffer buys ASR catch-up time before drop-oldest overruns.
         */
        private const val QUEUE_CAPACITY = 24
        private const val TAKE_TIMEOUT_MS = 250L
        private const val DIAG_INTERVAL_MS = 5_000L
        /** Newest window for one whisper/Sherpa call when catching up (~1.5 s). */
        /** Newest window for one ASR call when catching up (~1.5 s live; was 3 s). */
        const val DEFAULT_WINDOW_SAMPLES = 16_000 * 3 / 2
        /** If drained chunk count ≥ this, skip intermediate audio. */
        private const val BACKLOG_CHUNK_THRESHOLD = 4
    }
}
