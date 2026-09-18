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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Continuous PCM capture. A dedicated pump reads AudioRecord into a bounded queue;
 * ASR consumers pull windows and must never block [AudioRecord.read].
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
    private var pumpJob: Job? = null

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun overrunCount(): Long = overrunCount.get()

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
        pumpJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(READ_SAMPLES)
            var lastDiagAt = 0L
            while (isActive) {
                val ar = record ?: break
                val n = ar.read(buf, 0, buf.size)
                if (n > 0) {
                    val copy = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                    enqueueDropOldest(copy)
                    val c = chunkCount.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (now - lastDiagAt >= DIAG_INTERVAL_MS) {
                        lastDiagAt = now
                        Log.d(
                            TAG,
                            "capture chunks=$c overruns=${overrunCount.get()} " +
                                "q=${pcmQueue.size} rms=${rms(copy)}"
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
        /** Bounded queue ≈ 4 s of 0.5 s chunks. */
        private const val QUEUE_CAPACITY = 8
        private const val TAKE_TIMEOUT_MS = 250L
        private const val DIAG_INTERVAL_MS = 5_000L
    }
}
