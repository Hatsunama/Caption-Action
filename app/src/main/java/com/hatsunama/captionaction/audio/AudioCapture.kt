package com.hatsunama.captionaction.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
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
 * [drainToNewestWindow] accumulates a full engine window (Live ~2.25 s / Quality ~1.5 s)
 * before each ASR call (waiting on the queue while the pump is active). When behind, it
 * drops intermediate chunks and returns a contiguous newest window.
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

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun overrunCount(): Long = overrunCount.get()

    fun queueDepth(): Int = pcmQueue.size

    fun chunkCount(): Long = chunkCount.get()

    /** Most recent pump-chunk RMS (PCM16 mono). */
    fun lastRms(): Float = lastRms.get()

    fun startPlaybackCapture(projection: MediaProjection): Boolean {
        stopPumpAndRecord()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(
                CA_TAG,
                "EXCEPTION startPlaybackCapture return-false reason=api_below_Q " +
                    "api=${Build.VERSION.SDK_INT} captureMode=none"
            )
            return false
        }
        if (!hasRecordAudioPermission()) {
            Log.e(
                CA_TAG,
                "EXCEPTION startPlaybackCapture return-false reason=RECORD_AUDIO_denied " +
                    "captureMode=none"
            )
            return false
        }
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
            // Hard fail only on STATE_UNINITIALIZED — never on silence / zero energy.
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION startPlaybackCapture return-false reason=AudioRecord_STATE_UNINITIALIZED " +
                        "audioRecordState=${ar.state} minBuf=$minBuf captureMode=none"
                )
                ar.release()
                return false
            }
            ar.startRecording()
            val recState = ar.recordingState
            // Do not probe first-read energy: idle silence is a valid live session.
            record = ar
            usingPlaybackCapture = true
            Log.i(
                CA_TAG,
                "startPlaybackCapture ok captureMode=playback audioRecordState=${ar.state} " +
                    "recordingState=$recState usingPlaybackCapture=true"
            )
            true
        } catch (e: SecurityException) {
            Log.e(CA_TAG, "EXCEPTION startPlaybackCapture SecurityException captureMode=none", e)
            false
        } catch (e: UnsupportedOperationException) {
            Log.e(
                CA_TAG,
                "EXCEPTION startPlaybackCapture UnsupportedOperationException captureMode=none",
                e
            )
            false
        } catch (e: IllegalStateException) {
            Log.e(
                CA_TAG,
                "EXCEPTION startPlaybackCapture IllegalStateException captureMode=none",
                e
            )
            false
        } catch (e: IllegalArgumentException) {
            Log.e(
                CA_TAG,
                "EXCEPTION startPlaybackCapture IllegalArgumentException tryingCoreUsages",
                e
            )
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
                Log.e(
                    CA_TAG,
                    "EXCEPTION tryStartPlaybackCore return-false reason=AudioRecord_STATE_UNINITIALIZED " +
                        "audioRecordState=${ar.state} minBuf=$minBuf captureMode=none"
                )
                ar.release()
                return false
            }
            ar.startRecording()
            val recState = ar.recordingState
            // Silence / nothing playing is OK — pump waits for device audio.
            record = ar
            usingPlaybackCapture = true
            Log.i(
                CA_TAG,
                "tryStartPlaybackCore ok captureMode=playback audioRecordState=${ar.state} " +
                    "recordingState=$recState usingPlaybackCapture=true"
            )
            true
        } catch (e: Exception) {
            Log.e(CA_TAG, "EXCEPTION tryStartPlaybackCore catch captureMode=none", e)
            false
        }
    }

    /** Start the non-blocking capture pump. Call after [startPlaybackCapture] (device audio). */
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
                            "captureMode=playback usingPlaybackCapture=$usingPlaybackCapture " +
                                "chunks=$c overruns=${overrunCount.get()} " +
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
     * Pull a full ASR window (~[targetSamples] PCM; Live ~2.25 s / Quality ~1.5 s). Accumulates from
     * the queue, waiting (poll with timeout) while the pump is still capturing, so live
     * windows are never a single READ_SAMPLES crumb. Only returns a short/partial window when capture
     * has ended and the queue is empty (final flush). When behind (depth ≥ threshold or
     * total > target), keeps a contiguous newest ~[targetSamples] slice.
     */
    suspend fun drainToNewestWindow(targetSamples: Int = DEFAULT_WINDOW_SAMPLES): ShortArray? {
        val chunks = ArrayList<ShortArray>(QUEUE_CAPACITY)
        var total = 0

        val first = takeChunk() ?: return null
        chunks.add(first)
        total += first.size

        // Drain whatever is already queued, then wait for more until we hit targetSamples.
        while (total < targetSamples && coroutineContext.isActive) {
            var drained = false
            while (true) {
                val next = pcmQueue.poll() ?: break
                chunks.add(next)
                total += next.size
                drained = true
                if (total >= targetSamples) break
            }
            if (total >= targetSamples) break

            val capturing = record != null && pumpJob?.isActive == true
            if (!capturing && pcmQueue.isEmpty()) {
                // Capture ended — allow a short final window rather than blocking forever.
                break
            }

            val waited = pcmQueue.poll(TAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (waited != null) {
                chunks.add(waited)
                total += waited.size
            } else if (record == null && pcmQueue.isEmpty()) {
                break
            } else if (pumpJob?.isActive != true && pcmQueue.isEmpty()) {
                break
            }
            // else: timeout while still capturing — keep waiting for a full window
            if (!drained && waited == null && !capturing) break
        }

        if (chunks.isEmpty()) return null

        val depth = chunks.size
        val behind = depth >= BACKLOG_CHUNK_THRESHOLD || total > targetSamples
        if (behind && total > targetSamples) {
            Log.i(
                TAG,
                "drainToNewest: behind depth=$depth samples=$total overruns=${overrunCount.get()} " +
                    "→ keep newest ~${targetSamples} samples"
            )
        }

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
        if (out.size > targetSamples && (behind || total > targetSamples)) {
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
        /** Start-fail diagnostics — grep logcat for this tag. */
        private const val CA_TAG = "CaptionAction"
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
        /** Default drain target; engines override via preferredWindowSamples(). */
        const val DEFAULT_WINDOW_SAMPLES = 40_000
        /** Quality / whisper ~1.5 s — shorter so mid-device passes finish nearer realtime. */
        const val QUALITY_WINDOW_SAMPLES = 24_000
        /** Quality when behind (~1.125 s) — still ≥ native 1000 ms min. */
        const val QUALITY_WINDOW_BEHIND_SAMPLES = 18_000
        /** Live / Sherpa ~2.5 s — phrase-level SenseVoice (not 1.0–2.25 s crumbs). */
        const val LIVE_WINDOW_SAMPLES = 40_000
        /** If drained chunk count ≥ this, skip intermediate audio. */
        private const val BACKLOG_CHUNK_THRESHOLD = 4
    }
}
