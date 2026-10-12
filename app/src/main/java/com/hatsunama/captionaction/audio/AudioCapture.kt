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
 * [drainToNewestWindow] (Live) accumulates a full engine window then, when behind, drops
 * intermediate chunks and returns a contiguous newest window for video sync.
 * [drainFifoWindow] (Mic Translator) returns the next oldest-first window and never skips
 * mid-utterance audio under normal lag (optional absolute backlog cap only).
 */
class AudioCapture(private val context: Context) {

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    var usingPlaybackCapture: Boolean = false
        private set

    private val liveWindowDrainer = LiveWindowDrainer()
    private val pcmQueue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)
    private val overrunCount = AtomicLong(0)
    private val chunkCount = AtomicLong(0)
    private val lastRms = AtomicReference(0f)
    private var pumpJob: Job? = null
    @Volatile private var fifoBacklogWarned = false
    /** Leftover PCM from an overshot FIFO window — consumed before the next queue poll. */
    @Volatile private var fifoRemainder: ShortArray? = null

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
        // API contract: only MEDIA|GAME|UNKNOWN are capturable. Extra matchers
        // (VOICE_*/ASSIST*) are not capturable and have been implicated in OEM
        // AudioRecord STATE_UNINITIALIZED after Allow (Seeker hardInit evidence).
        val ok = buildAndStartPlayback(
            projection,
            usages = intArrayOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN
            ),
            attemptLabel = "capturableUsages"
        )
        if (ok) return true
        Log.e(
            CA_TAG,
            "EXCEPTION startPlaybackCapture capturableUsages failed — retry MEDIA-only"
        )
        return buildAndStartPlayback(
            projection,
            usages = intArrayOf(AudioAttributes.USAGE_MEDIA),
            attemptLabel = "mediaOnly"
        )
    }

    /**
     * Microphone capture for Mic Translator only.
     * Live captions must keep using [startPlaybackCapture] (device audio / MediaProjection) —
     * never call this from the Live overlay path.
     */
    fun startMicrophoneCapture(): Boolean {
        stopPumpAndRecord()
        if (!hasRecordAudioPermission()) {
            Log.e(
                CA_TAG,
                "EXCEPTION startMicrophoneCapture return-false reason=RECORD_AUDIO_denied " +
                    "captureMode=none"
            )
            return false
        }
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION startMicrophoneCapture return-false reason=minBuf_invalid " +
                        "minBuf=$minBuf captureMode=none"
                )
                return false
            }
            val bufBytes = captureBufferBytes(minBuf)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes
            )
            val state = ar.state
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION startMicrophoneCapture return-false " +
                        "reason=AudioRecord_STATE_UNINITIALIZED audioRecordState=$state " +
                        "minBuf=$minBuf bufBytes=$bufBytes captureMode=none"
                )
                ar.release()
                return false
            }
            ar.startRecording()
            val recState = ar.recordingState
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION startMicrophoneCapture recordingState_unexpected " +
                        "recordingState=$recState audioRecordState=$state (continuing)"
                )
            }
            record = ar
            usingPlaybackCapture = false
            Log.i(
                CA_TAG,
                "startMicrophoneCapture ok captureMode=microphone " +
                    "audioRecordState=$state recordingState=$recState usingPlaybackCapture=false"
            )
            true
        } catch (e: SecurityException) {
            Log.e(CA_TAG, "EXCEPTION startMicrophoneCapture SecurityException", e)
            false
        } catch (e: Exception) {
            Log.e(CA_TAG, "EXCEPTION startMicrophoneCapture catch", e)
            false
        }
    }

    /**
     * Build AudioPlaybackCaptureConfiguration + AudioRecord and startRecording.
     * Returns false on hard init failure (never on silence). Always Log.e on fail.
     */
    private fun buildAndStartPlayback(
        projection: MediaProjection,
        usages: IntArray,
        attemptLabel: String
    ): Boolean {
        return try {
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(projection)
            for (u in usages) configBuilder.addMatchingUsage(u)
            val config = configBuilder.build()
            Log.i(
                CA_TAG,
                "playbackConfig built attempt=$attemptLabel usages=${usages.joinToString(",")} " +
                    "projectionNull=false"
            )
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
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION buildAndStartPlayback return-false reason=minBuf_invalid " +
                        "minBuf=$minBuf attempt=$attemptLabel captureMode=none"
                )
                return false
            }
            val bufBytes = captureBufferBytes(minBuf)
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setAudioPlaybackCaptureConfig(config)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setContext(context)
            }
            val ar = builder.build()
            val state = ar.state
            // Hard fail only on STATE_UNINITIALIZED — never on silence / zero energy.
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION buildAndStartPlayback return-false " +
                        "reason=AudioRecord_STATE_UNINITIALIZED audioRecordState=$state " +
                        "minBuf=$minBuf bufBytes=$bufBytes attempt=$attemptLabel captureMode=none"
                )
                ar.release()
                return false
            }
            ar.startRecording()
            val recState = ar.recordingState
            if (recState != AudioRecord.RECORDSTATE_RECORDING) {
                // Log loudly but do not hard-fail solely on OEM recordingState quirks —
                // STATE_INITIALIZED + startRecording without throw is the hard-init contract.
                Log.e(
                    CA_TAG,
                    "EXCEPTION buildAndStartPlayback recordingState_unexpected " +
                        "recordingState=$recState audioRecordState=$state attempt=$attemptLabel " +
                        "(continuing; STATE_INITIALIZED)"
                )
            }
            // Do not probe first-read energy: idle silence is a valid live session.
            record = ar
            usingPlaybackCapture = true
            Log.i(
                CA_TAG,
                "buildAndStartPlayback ok attempt=$attemptLabel captureMode=playback " +
                    "audioRecordState=$state recordingState=$recState usingPlaybackCapture=true"
            )
            true
        } catch (e: SecurityException) {
            Log.e(
                CA_TAG,
                "EXCEPTION buildAndStartPlayback SecurityException attempt=$attemptLabel " +
                    "captureMode=none",
                e
            )
            false
        } catch (e: UnsupportedOperationException) {
            Log.e(
                CA_TAG,
                "EXCEPTION buildAndStartPlayback UnsupportedOperationException " +
                    "attempt=$attemptLabel captureMode=none",
                e
            )
            false
        } catch (e: IllegalStateException) {
            Log.e(
                CA_TAG,
                "EXCEPTION buildAndStartPlayback IllegalStateException attempt=$attemptLabel " +
                    "captureMode=none",
                e
            )
            false
        } catch (e: IllegalArgumentException) {
            Log.e(
                CA_TAG,
                "EXCEPTION buildAndStartPlayback IllegalArgumentException attempt=$attemptLabel " +
                    "captureMode=none",
                e
            )
            false
        } catch (e: Exception) {
            Log.e(
                CA_TAG,
                "EXCEPTION buildAndStartPlayback catch attempt=$attemptLabel captureMode=none",
                e
            )
            false
        }
    }

    /** Start the non-blocking capture pump. Call after [startPlaybackCapture] or [startMicrophoneCapture]. */
    fun startPump(scope: CoroutineScope) {
        pumpJob?.cancel()
        pcmQueue.clear()
        overrunCount.set(0)
        chunkCount.set(0)
        lastRms.set(0f)
        fifoBacklogWarned = false
        fifoRemainder = null
        liveWindowDrainer.reset()
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
                            "captureMode=${if (usingPlaybackCapture) "playback" else "microphone"} usingPlaybackCapture=$usingPlaybackCapture " +
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
     * Pull a full Live window, preserving normal read-chunk overshoot. If newer
     * chunks are already queued after filling it, drain that bounded backlog and
     * return its exact newest contiguous window for video sync. Short windows are
     * returned only when capture ends. Mic retains its separate FIFO policy.
     */
    suspend fun drainToNewestWindow(targetSamples: Int = DEFAULT_WINDOW_SAMPLES): ShortArray? =
        liveWindowDrainer.drain(
            targetSamples = targetSamples,
            takeChunk = { takeChunk() },
            queuedChunkCount = { pcmQueue.size },
            pollChunk = { pcmQueue.poll() },
            onDropSamples = { dropped ->
                Log.i(TAG, "drainToNewest: dropped=$dropped keep=$targetSamples overruns=${overrunCount.get()}")
            }
        )

    /**
     * Mic Translator FIFO drain: contiguous oldest-first accumulation to ~[targetSamples]
     * (same wait/poll style as [drainToNewestWindow]). Never trims to newest when behind —
     * returns the next front window; excess stays queued for the following call.
     * If absolute backlog exceeds ~10 s of PCM, drop oldest excess once (Log.w) to avoid OOM;
     * under normal lag mid-utterance audio is not skipped.
     * Live captions must keep using [drainToNewestWindow].
     */
    suspend fun drainFifoWindow(targetSamples: Int = DEFAULT_WINDOW_SAMPLES): ShortArray? {
        trimFifoBacklogIfNeeded()

        val chunks = ArrayList<ShortArray>(QUEUE_CAPACITY)
        var total = 0

        // Consume prior overshoot before polling the pump queue.
        fifoRemainder?.let { rem ->
            fifoRemainder = null
            chunks.add(rem)
            total += rem.size
        }
        if (total < targetSamples) {
            val first = takeChunk() ?: run {
                if (chunks.isEmpty()) return null
                // Capture ended with only a remainder — return what we have.
                return concatFifo(chunks, targetSamples)
            }
            chunks.add(first)
            total += first.size
        }

        while (total < targetSamples && coroutineContext.isActive) {
            var drained = false
            while (total < targetSamples) {
                val next = pcmQueue.poll() ?: break
                chunks.add(next)
                total += next.size
                drained = true
            }
            if (total >= targetSamples) break

            val capturing = record != null && pumpJob?.isActive == true
            if (!capturing && pcmQueue.isEmpty()) {
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
            if (!drained && waited == null && !capturing) break
        }

        if (chunks.isEmpty()) return null

        val depth = pcmQueue.size
        if (depth >= 4 || total > targetSamples) {
            Log.i(
                TAG,
                "drainFifo: depthQ=$depth drainedSamples=$total target=$targetSamples " +
                    "overruns=${overrunCount.get()} (oldest-first, no newest trim)"
            )
        }

        return concatFifo(chunks, targetSamples)
    }

    /** Concatenate oldest-first; stash overshoot as [fifoRemainder] for the next call. */
    private fun concatFifo(chunks: List<ShortArray>, targetSamples: Int): ShortArray? {
        if (chunks.isEmpty()) return null
        var outLen = 0
        for (c in chunks) outLen += c.size
        val out = ShortArray(outLen)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        if (out.size <= targetSamples) return out
        fifoRemainder = out.copyOfRange(targetSamples, out.size)
        return out.copyOfRange(0, targetSamples)
    }

    /**
     * If queued PCM exceeds ~10 s (~[FIFO_BACKLOG_CAP_CHUNKS] × READ_SAMPLES), drop oldest
     * excess so the queue cannot grow without bound while ASR is slow. Logs once per pump.
     */
    private fun trimFifoBacklogIfNeeded() {
        var dropped = 0
        while (pcmQueue.size > FIFO_BACKLOG_CAP_CHUNKS) {
            val oldest = pcmQueue.poll() ?: break
            dropped += oldest.size
        }
        if (dropped > 0 && !fifoBacklogWarned) {
            fifoBacklogWarned = true
            Log.w(
                TAG,
                "drainFifo: backlog >~10s — dropped oldest $dropped samples " +
                    "(capChunks=$FIFO_BACKLOG_CAP_CHUNKS); subsequent windows remain FIFO"
            )
        }
    }

    fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        stopPumpAndRecord()
        pcmQueue.clear()
        fifoRemainder = null
        liveWindowDrainer.reset()
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
        /** Optional SenseVoice lane ~3.0 s — phrase-level (not default Live; Live uses whisper Tiny windows). */
        const val LIVE_WINDOW_SAMPLES = 48_000
        /** Mic FIFO absolute backlog cap ~10 s @ 16 kHz (~20 × 0.5 s chunks). */
        private const val FIFO_BACKLOG_CAP_CHUNKS = 20
    }
}
