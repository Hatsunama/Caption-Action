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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
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

    /** Every producer/consumer holds one owner, never a queue belonging to a later start. */
    private class Session(val record: AudioRecord, val playback: Boolean) {
        val liveWindowDrainer = LiveWindowDrainer()
        val pcmQueue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)
        val overrunCount = AtomicLong(0)
        val chunkCount = AtomicLong(0)
        val lastRms = AtomicReference(0f)
        @Volatile var pumpJob: Job? = null
        @Volatile var retired = false
        @Volatile var fifoBacklogWarned = false
        @Volatile var fifoRemainder: ShortArray? = null
        // Protected by this Session's monitor; never held across a hardware read/stop.
        var pumpRunning = false
        var stopFinished = false
        var released = false
    }

    @Volatile private var session: Session? = null

    val usingPlaybackCapture: Boolean
        get() = session?.playback == true

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun overrunCount(): Long = session?.overrunCount?.get() ?: 0L

    fun queueDepth(): Int = session?.pcmQueue?.size ?: 0

    fun chunkCount(): Long = session?.chunkCount?.get() ?: 0L

    /** Most recent pump-chunk RMS (PCM16 mono). */
    fun lastRms(): Float = session?.lastRms?.get() ?: 0f

    @Synchronized
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
    @Synchronized
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
        var allocated: AudioRecord? = null
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
            allocated = ar
            val state = ar.state
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION startMicrophoneCapture return-false " +
                        "reason=AudioRecord_STATE_UNINITIALIZED audioRecordState=$state " +
                        "minBuf=$minBuf bufBytes=$bufBytes captureMode=none"
                )
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
            session = Session(ar, playback = false)
            allocated = null
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
        } finally {
            allocated?.let { releaseRecord(it) }
        }
    }

    /**
     * Build AudioPlaybackCaptureConfiguration + AudioRecord and startRecording.
     * Returns false on hard init failure (never on silence). Always Log.e on fail.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildAndStartPlayback(
        projection: MediaProjection,
        usages: IntArray,
        attemptLabel: String
    ): Boolean {
        var allocated: AudioRecord? = null
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
            allocated = ar
            val state = ar.state
            // Hard fail only on STATE_UNINITIALIZED — never on silence / zero energy.
            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                    CA_TAG,
                    "EXCEPTION buildAndStartPlayback return-false " +
                        "reason=AudioRecord_STATE_UNINITIALIZED audioRecordState=$state " +
                        "minBuf=$minBuf bufBytes=$bufBytes attempt=$attemptLabel captureMode=none"
                )
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
            session = Session(ar, playback = true)
            allocated = null
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
        } finally {
            allocated?.let { releaseRecord(it) }
        }
    }

    /** Start the non-blocking capture pump. Call after [startPlaybackCapture] or [startMicrophoneCapture]. */
    fun startPump(scope: CoroutineScope) = startPump(scope) {}

    /** Terminal read failure retires its capture and reports once on Main, independently of drains. */
    @Synchronized
    fun startPump(scope: CoroutineScope, onFailure: () -> Unit) {
        val owner = session ?: return
        synchronized(owner) {
            // One reader per record, including repeated startPump calls.
            if (owner.retired || owner.pumpJob != null) return
            owner.pumpRunning = true
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val buf = ShortArray(READ_SAMPLES)
                var lastDiagAt = 0L
                var readFailed = false
                while (isActive && !owner.retired) {
                    val n = try {
                        owner.record.read(buf, 0, buf.size)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord.read failed errorType=${e.javaClass.simpleName}")
                        readFailed = true
                        break
                    }
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        synchronized(owner) {
                            if (owner.retired || !isActive) return@launch
                            owner.lastRms.set(rms(copy))
                            enqueueDropOldest(owner, copy)
                            val count = owner.chunkCount.incrementAndGet()
                            val now = System.currentTimeMillis()
                            if (now - lastDiagAt >= DIAG_INTERVAL_MS) {
                                lastDiagAt = now
                                Log.d(TAG, "captureMode=${if (owner.playback) "playback" else "microphone"} " +
                                    "usingPlaybackCapture=${owner.playback} chunks=$count " +
                                    "overruns=${owner.overrunCount.get()} q=${owner.pcmQueue.size} " +
                                    "rms=${owner.lastRms.get()}")
                            }
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error=$n")
                        readFailed = true
                        break
                    }
                }
                if (readFailed && isActive && !owner.retired) {
                    // A failed pump may precede ASR/model readiness. Do not depend on a drain
                    // noticing EOF, and do not notify from under either capture monitor.
                    scope.launch(Dispatchers.Main) {
                        val report = synchronized(this@AudioCapture) {
                            if (!isActive || session !== owner || owner.retired) false else {
                                stopPumpAndRecord()
                                true
                            }
                        }
                        if (report) onFailure()
                    }
                }
            }
            owner.pumpJob = job
            // Unlike a coroutine-body finally, this also runs if its scope was already cancelled.
            job.invokeOnCompletion {
                synchronized(owner) {
                    owner.pumpRunning = false
                    releaseIfFinished(owner)
                }
            }
            job.start()
        }
    }

    /**
     * Pull one PCM chunk for the ASR consumer. Returns null on timeout while still capturing,
     * or when the pump has stopped and the queue is empty.
     */
    suspend fun takeChunk(timeoutMs: Long = TAKE_TIMEOUT_MS): ShortArray? {
        val owner = session ?: return null
        return takeChunk(owner, timeoutMs)
    }

    private suspend fun takeChunk(owner: Session, timeoutMs: Long = TAKE_TIMEOUT_MS): ShortArray? {
        while (coroutineContext.isActive && !owner.retired) {
            val chunk = owner.pcmQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (!coroutineContext.isActive || owner.retired) return null
            if (chunk != null) return chunk
            if (owner.pumpJob?.isActive != true && owner.pcmQueue.isEmpty()) return null
        }
        return null
    }

    /**
     * Pull a full Live window, preserving normal read-chunk overshoot. If newer
     * chunks are already queued after filling it, drain that bounded backlog and
     * return its exact newest contiguous window for video sync. Short windows are
     * returned only when capture ends. Mic retains its separate FIFO policy.
     */
    suspend fun drainToNewestWindow(targetSamples: Int = DEFAULT_WINDOW_SAMPLES): ShortArray? {
        val owner = session ?: return null
        val result = owner.liveWindowDrainer.drain(
            targetSamples = targetSamples,
            takeChunk = { takeChunk(owner) },
            queuedChunkCount = { owner.pcmQueue.size },
            pollChunk = { owner.pcmQueue.poll() },
            onDropSamples = { dropped ->
                Log.i(TAG, "drainToNewest: dropped=$dropped keep=$targetSamples overruns=${owner.overrunCount.get()}")
            }
        )
        return if (owner.retired || !coroutineContext.isActive) null else result
    }

    /**
     * Mic Translator FIFO drain: contiguous oldest-first accumulation to ~[targetSamples]
     * (same wait/poll style as [drainToNewestWindow]). Never trims to newest when behind —
     * returns the next front window; excess stays queued for the following call.
     * If absolute backlog exceeds ~10 s of PCM, drop oldest excess once (Log.w) to avoid OOM;
     * under normal lag mid-utterance audio is not skipped.
     * Live captions must keep using [drainToNewestWindow].
     */
    suspend fun drainFifoWindow(targetSamples: Int = DEFAULT_WINDOW_SAMPLES): ShortArray? {
        require(targetSamples > 0)
        val active = coroutineContext
        if (!active.isActive) return null
        val owner = session ?: return null
        val pending = synchronized(owner) {
            if (owner.retired || !active.isActive) return null
            trimFifoBacklogIfNeeded(owner, active)
            // Keep the previous remainder owned until a live drain commits its result.
            // Cancellation while polling must not consume uncommitted remainder.
            owner.fifoRemainder
        }

        val chunks = ArrayList<ShortArray>(QUEUE_CAPACITY)
        var total = 0

        // Consume prior overshoot before polling the pump queue.
        pending?.let { rem ->
            chunks.add(rem)
            total += rem.size
        }
        if (total < targetSamples) {
            val first = takeChunk(owner) ?: run {
                if (chunks.isEmpty()) return null
                // Capture ended with only a remainder — return what we have.
                return concatFifo(owner, chunks, targetSamples)
            }
            chunks.add(first)
            total += first.size
        }

        while (total < targetSamples && coroutineContext.isActive && !owner.retired) {
            var drained = false
            while (total < targetSamples) {
                val next = owner.pcmQueue.poll() ?: break
                chunks.add(next)
                total += next.size
                drained = true
            }
            if (total >= targetSamples) break

            val capturing = !owner.retired && owner.pumpJob?.isActive == true
            if (!capturing && owner.pcmQueue.isEmpty()) {
                break
            }

            val waited = owner.pcmQueue.poll(TAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (waited != null) {
                chunks.add(waited)
                total += waited.size
            } else if (owner.retired && owner.pcmQueue.isEmpty()) {
                break
            } else if (owner.pumpJob?.isActive != true && owner.pcmQueue.isEmpty()) {
                break
            }
            if (!drained && waited == null && !capturing) break
        }

        if (chunks.isEmpty()) return null

        val depth = owner.pcmQueue.size
        if (depth >= 4 || total > targetSamples) {
            Log.i(
                TAG,
                "drainFifo: depthQ=$depth drainedSamples=$total target=$targetSamples " +
                    "overruns=${owner.overrunCount.get()} (oldest-first, no newest trim)"
            )
        }

        return concatFifo(owner, chunks, targetSamples)
    }

    /** Concatenate oldest-first; stash overshoot as the session remainder for the next call. */
    private suspend fun concatFifo(owner: Session, chunks: List<ShortArray>, targetSamples: Int): ShortArray? {
        if (chunks.isEmpty()) return null
        var outLen = 0
        for (c in chunks) outLen += c.size
        val out = ShortArray(outLen)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        val active = coroutineContext
        return synchronized(owner) {
            if (owner.retired || !active.isActive) return null
            if (out.size <= targetSamples) {
                owner.fifoRemainder = null
                out
            } else {
                owner.fifoRemainder = out.copyOfRange(targetSamples, out.size)
                out.copyOfRange(0, targetSamples)
            }
        }
    }

    /**
     * If queued PCM exceeds ~10 s (~[FIFO_BACKLOG_CAP_CHUNKS] × READ_SAMPLES), drop oldest
     * excess so the queue cannot grow without bound while ASR is slow. Logs once per pump.
     */
    private fun trimFifoBacklogIfNeeded(owner: Session, active: CoroutineContext) {
        var dropped = 0
        while (!owner.retired && active.isActive && owner.pcmQueue.size > FIFO_BACKLOG_CAP_CHUNKS) {
            val oldest = owner.pcmQueue.poll() ?: break
            dropped += oldest.size
        }
        if (dropped > 0 && !owner.fifoBacklogWarned) {
            owner.fifoBacklogWarned = true
            Log.w(
                TAG,
                "drainFifo: backlog >~10s — dropped oldest $dropped samples " +
                    "(capChunks=$FIFO_BACKLOG_CAP_CHUNKS); subsequent windows remain FIFO"
            )
        }
    }

    @Synchronized
    fun stop() {
        stopPumpAndRecord()
    }

    /**
     * Preserve platform stop-before-next-start ordering, but never join a blocking read on UI.
     * OEM stop() itself may block, as before. A read that never returns retains its retired
     * record; no timeout may free a resource still in use by native capture.
     */
    private fun stopPumpAndRecord() {
        val owner = session ?: return
        session = null
        synchronized(owner) {
            owner.retired = true
            owner.pumpJob?.cancel()
            owner.pcmQueue.clear()
            owner.fifoRemainder = null
            owner.liveWindowDrainer.reset()
        }
        try {
            owner.record.stop()
        } catch (_: IllegalStateException) {
        } finally {
            synchronized(owner) {
                owner.stopFinished = true
                releaseIfFinished(owner)
            }
        }
    }

    /** Called with owner's monitor. Only the completed pump and stop can relinquish it. */
    private fun releaseIfFinished(owner: Session) {
        if (owner.retired && owner.stopFinished && !owner.pumpRunning && !owner.released) {
            owner.released = true
            releaseRecord(owner.record)
        }
    }

    private fun releaseRecord(record: AudioRecord) {
        try {
            record.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.release failed", e)
        }
    }

    private fun enqueueDropOldest(owner: Session, chunk: ShortArray) {
        if (owner.pcmQueue.offer(chunk)) return
        owner.pcmQueue.poll()
        owner.overrunCount.incrementAndGet()
        if (!owner.pcmQueue.offer(chunk)) {
            owner.overrunCount.incrementAndGet()
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
