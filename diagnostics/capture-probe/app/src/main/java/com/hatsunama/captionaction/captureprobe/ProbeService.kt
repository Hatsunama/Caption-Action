package com.hatsunama.captionaction.captureprobe

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Isolated platform component probe. NOT production Live/AudioCapture/ASR runtime proof. */
class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val deadline = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var active = false
    @Volatile private var finished = false
    @Volatile private var safetyArmed = false
    @Volatile private var sourcePlaying = false
    private val phaseSequence = ProbePhaseSequence()
    private var speakerId = -1
    private val reports = mutableListOf<String>()
    private val audio get() = getSystemService(AudioManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null
    private fun safeRoute(): Boolean = safeOutput(
        audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.isVolumeFixed,
        !safeAvailableOutputs(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }),
        audio.mode == AudioManager.MODE_NORMAL)
    private fun hardAbort(reason: String) {
        ProbeState.status = "ABORTED: $reason. No successful capture claim."
        Log.e("CaptionCaptureProbe", "ABORT reason=$reason")
        // Kill only this disposable probe process, not any other app. Android releases its audio.
        Process.killProcess(Process.myPid())
    }
    private val volumeGuard = object : Runnable {
        override fun run() {
            if (!safetyArmed) return
            if (!safeRoute()) hardAbort("unsafe_output_state")
            else handler.postDelayed(this, 20)
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { checkRouteEvent() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { checkRouteEvent() }
    }
    private fun checkRouteEvent() {
        if (safetyArmed && (!safeRoute() || audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.id == speakerId })) {
            hardAbort("output_devices_changed")
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("probe", "Muted capture QA", NotificationManager.IMPORTANCE_LOW))
        startForeground(41, Notification.Builder(this, "probe").setContentTitle("Muted capture platform probe")
            .setContentText("Owned synthetic audio only • maximum 30 seconds")
            .setSmallIcon(android.R.drawable.ic_media_play).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val consent = intent?.getParcelableExtra<Intent>("consent")
        val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (consent == null || speaker == null || !safeRoute()) {
            ProbeState.status = "BLOCKED: need consent, media volume zero, normal mode and built-in outputs only."
            deadline.shutdownNow(); stopSelf(); return START_NOT_STICKY
        }
        speakerId = speaker.id; active = true; safetyArmed = true; ProbeState.running = true
        // Independent of coroutines/UI/native cleanup: do not cancel until all teardown returns.
        deadline.schedule({ if (safetyArmed) hardAbort("hard_30_second_deadline") }, 30, TimeUnit.SECONDS)
        audio.registerAudioDeviceCallback(devices, handler); handler.post(volumeGuard)
        // Enter finally even if Stop cancels this job before its first IO dispatch.
        scope.launch(start = CoroutineStart.ATOMIC) {
            var outcome: Boolean? = null
            val runJob = currentCoroutineContext()[Job]!!
            try {
                ensureActive() // Cancelled-before-dispatch runs empty cleanup, never capture setup.
                val mp = checkNotNull(getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(intent.getIntExtra("code", 0), consent))
                projection = mp
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (projection !== mp) return
                        ProbeState.status = "Sharing revoked. Probe stopped."; stopSelf()
                    }
                }, handler)
                ensureActive()
                var allPassed = true
                for (phase in ProbePhase.values()) {
                    ensureActive(); check(active)
                    allPassed = runPhase(mp, speaker, phase) && allPassed
                }
                ensureActive(); check(active)
                outcome = allPassed
            } catch (_: CancellationException) {
                // onDestroy/revocation already supplies status; finally still owns native teardown.
            } catch (e: Exception) {
                ProbeState.status = "FAILED: ${e.javaClass.simpleName}. No Live/ASR conclusion.\n" + reports.joinToString("\n")
                Log.e("CaptionCaptureProbe", "failed errorType=${e.javaClass.simpleName}")
            } finally {
                active = false
                val mp = projection; projection = null
                val completed = finalizeProbeSession(
                    nativeTeardownVerified = phaseSequence.canDisarm(),
                    stopProjection = { mp?.stop() },
                    disarm = {
                        if (!safeRoute()) hardAbort("unsafe_output_state_at_teardown")
                        safetyArmed = false
                        ProbeState.running = false
                        handler.removeCallbacks(volumeGuard); audio.unregisterAudioDeviceCallback(devices)
                        deadline.shutdownNow()
                    },
                    publish = outcome?.let { passed -> {
                        finished = true
                        ProbeState.status = (if (passed) "PLATFORM CAPTURE COMPONENT PASS" else "PLATFORM CAPTURE FAIL/INCONCLUSIVE") +
                            "\nNot production Live, ASR or cross-app acceptance.\n" + reports.joinToString("\n\n")
                        Log.i("CaptionCaptureProbe", "overall=${if (passed) "PASS" else "FAIL_INCONCLUSIVE"} mediaIndex=0 ownUid=true platformOnly=true teardownVerified=true")
                    } },
                    mayPublish = { runJob.isActive }
                )
                if (!completed) {
                    ProbeState.status = "TEARDOWN UNVERIFIED: hard watchdog remains armed. No successful claim."
                    Log.e("CaptionCaptureProbe", "teardown_unverified watchdogArmed=true")
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun runPhase(mp: MediaProjection, speaker: AudioDeviceInfo, phase: ProbePhase): Boolean {
        check(safeRoute())
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUid(Process.myUid())
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).build()
        val format = AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("playback_audio_permission_not_granted")
        }
        phaseSequence.beginPhase()
        val record = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum, 16000))
            .setAudioPlaybackCaptureConfig(config).build()
        var source: AudioTrack? = null
        val pcm = ShortArray(640)
        val synthetic = ShortArray(16000 * 8) { i ->
            if (phase.tone) (6000.0 * sin(2.0 * PI * 733.0 * i / 16000.0)).toInt().toShort() else 0
        }
        val metrics = ProbeMetrics()
        var frames = 0L
        val routing = AudioRouting.OnRoutingChangedListener { routed ->
            val device = routed.routedDevice
            if (safetyArmed && sourcePlaying && track === routed &&
                !isExpectedSpeakerRoute(speakerId, device?.id, device?.type)) hardAbort("source_route_changed")
        }
        try {
            currentCoroutineContext().ensureActive(); check(active)
            check(record.state == AudioRecord.STATE_INITIALIZED)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(if (phase.allowed) AudioAttributes.ALLOW_CAPTURE_BY_ALL else AudioAttributes.ALLOW_CAPTURE_BY_NONE).build()
            source = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(synthetic.size * 2).build()
            track = source
            check(source.setPreferredDevice(speaker))
            check(source.setVolume(1f) == AudioTrack.SUCCESS)
            check(source.write(synthetic, 0, synthetic.size) == synthetic.size)
            synthetic.fill(0)
            check(source.state == AudioTrack.STATE_INITIALIZED)
            check(safeRoute()); currentCoroutineContext().ensureActive(); check(active)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            source.addOnRoutingChangedListener(routing, handler)
            sourcePlaying = true
            source.play()
            val start = SystemClock.elapsedRealtime()
            ProbeState.status = "Running ${phase.name}: own UID only, media volume 0, gain 1.\n" + reports.joinToString("\n")
            while (SystemClock.elapsedRealtime() - start < 6000) {
                currentCoroutineContext().ensureActive(); check(active)
                if (!safeRoute()) hardAbort("unsafe_output_state")
                val routedDevice = source.routedDevice
                if (SystemClock.elapsedRealtime() - start >= 300 &&
                    !isExpectedSpeakerRoute(speakerId, routedDevice?.id, routedDevice?.type)) hardAbort("wrong_source_route")
                val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                try {
                    check(n >= 0)
                    if (n > 0 && SystemClock.elapsedRealtime() - start >= 1500) metrics.accept(pcm, n)
                } finally { pcm.fill(0) }
                delay(10)
            }
            frames = source.playbackHeadPosition.toLong() and 0xffffffffL
        } finally {
            pcm.fill(0); synthetic.fill(0)
            val released = releaseProbeAudio(
                pauseSource = { source?.pause() },
                releaseSource = { source?.release() },
                stopRecord = { record.stop() },
                releaseRecord = { record.release() },
                onSourcePaused = { sourcePlaying = false },
                onSourceReleased = {
                    sourcePlaying = false
                    try { source?.removeOnRoutingChangedListener(routing) } catch (_: Exception) {}
                    track = null
                }
            )
            phaseSequence.finishPhase(released)
            // A blocked native teardown cannot suppress the independent hard deadline.
        }
        val passed = phasePassed(phase, metrics.samples, metrics.rms(), frames, metrics.toneFraction())
        val report = String.format(Locale.US,
            "%s result=%s sourceFrames=%d samples=%d nonzero=%d rms=%.3f peak=%d toneFraction=%.4f sha256=%s",
            phase.name, if (passed) "PASS" else "FAIL_INCONCLUSIVE", frames, metrics.samples, metrics.nonzero,
            metrics.rms(), metrics.peak, metrics.toneFraction(), metrics.finishHash())
        reports.add(report); Log.i("CaptionCaptureProbe", report)
        return passed
    }
    override fun onDestroy() {
        active = false
        if (!finished && ProbeState.running) ProbeState.status = "Stopped. No successful capture claim."
        // Never run potentially blocking native teardown on Main. IO finally owns all resources.
        scope.cancel()
        // Keep independent route/volume guards armed until IO verifies native teardown.
        super.onDestroy()
    }
}
