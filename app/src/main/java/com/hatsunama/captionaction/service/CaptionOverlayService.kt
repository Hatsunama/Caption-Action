package com.hatsunama.captionaction.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.audio.AudioCapture
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.data.SubtitleFileRecorder
import com.hatsunama.captionaction.inference.AsrJunkFilter
import com.hatsunama.captionaction.inference.CaptionDisplay
import com.hatsunama.captionaction.inference.CaptionResult
import com.hatsunama.captionaction.inference.EnsureResult
import com.hatsunama.captionaction.inference.InferenceEngine
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.inference.MlKitTranslationEngine
import com.hatsunama.captionaction.inference.SubtitleComposer
import com.hatsunama.captionaction.ui.home.HomeActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class CaptionOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var asrJob: Job? = null
    /** Concurrent MT jobs — never cancel prior on new caption (seq guards apply). */
    private val mtJobs = CopyOnWriteArrayList<Job>()
    /** Subtitle file: append once per captionSeq (final primary, not ZH then EN dup). */
    private var subtitleAppendSeq = -1L

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var audioCapture: AudioCapture
    private val composer = SubtitleComposer()
    private var translation: MlKitTranslationEngine? = null
    private lateinit var subtitleRecorder: SubtitleFileRecorder
    private var engine: InferenceEngine? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    /** Tiny VirtualDisplay keeps MediaProjection alive (required API 34+; audio-only). */
    private var projectionVirtualDisplay: VirtualDisplay? = null
    private var projectionImageReader: ImageReader? = null

    @Volatile private var hasRealCaption = false
    @Volatile private var tearingDown = false
    private var fontIndex = 0
    @Volatile private var captionSeq = 0L
    /** Last published ASR primary (normalized) — suppress identical / near-dup loops. */
    private var lastPublishedNorm = ""
    private var lastPublishedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioCapture = AudioCapture(this)
        subtitleRecorder = SubtitleFileRecorder(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                // mediaProjection FGS type required before getMediaProjection (API 34+).
                startForegroundForProjection()
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                // Fresh grant only — release any prior projection; never reuse a consumed token.
                releaseProjection()
                audioCapture.stop()
                val wanted = StartHandoffGate.isProjectionResultGranted(resultCode, data != null)
                val projection = claimProjectionSync(resultCode, data)
                scope.launch { beginSession(projection, wantedProjection = wanted) }
                // NOT sticky: sticky redelivery reuses a single-use MediaProjection Intent → false "allow".
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundForProjection() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Restore 0.3.6 mic|mediaProjection FGS types. 0.3.7 dropped microphone and
            // Start began failing after Allow on Seeker + Samsung (AudioRecord playback
            // capture still needs the microphone FGS type alongside mediaProjection).
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val typeNames = mutableListOf("mediaProjection")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                typeNames += "microphone"
            }
            if (Build.VERSION.SDK_INT >= 34) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                typeNames += "specialUse"
            }
            Log.i(
                DIAG_TAG,
                "startForeground fgsTypes=${typeNames.joinToString("|")} " +
                    "typeBits=0x${Integer.toHexString(type)} api=${Build.VERSION.SDK_INT}"
            )
            try {
                startForeground(NOTIF_ID, notification, type)
            } catch (t: Exception) {
                Log.e(
                    DIAG_TAG,
                    "EXCEPTION startForeground failed fgsTypes=${typeNames.joinToString("|")} " +
                        "typeBits=0x${Integer.toHexString(type)}",
                    t
                )
                throw t
            }
        } else {
            Log.i(DIAG_TAG, "startForeground legacy(no type) api=${Build.VERSION.SDK_INT}")
            try {
                startForeground(NOTIF_ID, notification)
            } catch (t: Exception) {
                Log.e(DIAG_TAG, "EXCEPTION startForeground legacy failed", t)
                throw t
            }
        }
    }

    private fun claimProjectionSync(resultCode: Int, data: Intent?): MediaProjection? {
        if (!StartHandoffGate.isProjectionResultGranted(resultCode, data != null)) {
            Log.e(
                DIAG_TAG,
                "EXCEPTION getMediaProjection return-null reason=not_granted " +
                    "resultCode=$resultCode dataNull=${data == null} projectionNull=true"
            )
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(
                DIAG_TAG,
                "EXCEPTION getMediaProjection return-null reason=api_below_Q " +
                    "api=${Build.VERSION.SDK_INT} projectionNull=true"
            )
            return null
        }
        return try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Single-use token from this Start's createScreenCaptureIntent result only.
            val projection = mpm.getMediaProjection(resultCode, data!!)
            if (projection == null) {
                Log.e(
                    DIAG_TAG,
                    "EXCEPTION getMediaProjection return-null reason=MediaProjectionManager_null " +
                        "resultCode=$resultCode projectionNull=true"
                )
                return null
            }
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    if (!tearingDown) {
                        scope.launch(Dispatchers.Main) {
                            failSession(getString(R.string.error_projection_stopped))
                        }
                    }
                }
            }
            // API 34+: register before AudioPlaybackCaptureConfiguration / virtual display.
            projection.registerCallback(cb, Handler(Looper.getMainLooper()))
            projectionCallback = cb
            mediaProjection = projection
            Log.i(
                DIAG_TAG,
                "getMediaProjection ok projectionNull=false resultCode=$resultCode"
            )
            projection
        } catch (t: Exception) {
            Log.e(DIAG_TAG, "EXCEPTION getMediaProjection catch claimProjection failed", t)
            null
        }
    }

    private suspend fun beginSession(projection: MediaProjection?, wantedProjection: Boolean) {
        val app = application as CaptionActionApp
        val settings = app.settings.current()
        fontIndex = settings.fontIndex

        Log.i(
            DIAG_TAG,
            "beginSession enter projectionNull=${projection == null} " +
                "wantedProjection=$wantedProjection api=${Build.VERSION.SDK_INT}"
        )

        // Device audio only — never mic. Fail before overlay if capture unavailable.
        if (projection == null) {
            val kind = ProjectionFreshStart.failKind(
                wantedProjection = wantedProjection,
                projectionClaimed = false,
                playbackCaptureStarted = false
            )
            val msg = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                    getString(R.string.error_requires_android_10)
                kind == ProjectionFreshStart.FailKind.CLAIM_FAILED_AFTER_ALLOW ->
                    getString(R.string.error_projection_claim_failed)
                else -> getString(R.string.error_projection_required)
            }
            Log.e(
                DIAG_TAG,
                "EXCEPTION beginSession return-false reason=projection_null " +
                    "projectionNull=true keepAliveCreated=false captureMode=none " +
                    "wantedProjection=$wantedProjection failKind=$kind api=${Build.VERSION.SDK_INT}"
            )
            failSessionReturnHome(msg)
            return
        }
        // Seeker evidence (0.3.9): keepAliveCreated=true + hardInit=true — VirtualDisplay
        // succeeded but AudioRecord playback init failed. 0.3.6 (last known good) started
        // AudioRecord *without* a prior keep-alive; 0.3.9 put AUTO_MIRROR VD first.
        // Fix: re-assert mic|mediaProjection FGS, AudioRecord first, then flag-0 keep-alive;
        // if AudioRecord still fails, attach keep-alive and retry once.
        startForegroundForProjection()
        var started = audioCapture.startPlaybackCapture(projection)
        Log.i(
            DIAG_TAG,
            "playbackCapture attempt=first started=$started " +
                "usingPlaybackCapture=${audioCapture.usingPlaybackCapture} " +
                "hasRecordAudio=${audioCapture.hasRecordAudioPermission()}"
        )
        var keepAliveFailed = !ensureProjectionKeepAliveDisplay(projection)
        var keepAliveCreated = !keepAliveFailed
        Log.i(
            DIAG_TAG,
            "keepAlive created=$keepAliveCreated keepAliveFailed=$keepAliveFailed " +
                "afterFirstPlaybackAttempt=true"
        )
        if (!started) {
            Log.e(
                DIAG_TAG,
                "EXCEPTION beginSession first playbackCapture failed — " +
                    "retry after keepAlive created=$keepAliveCreated"
            )
            // Re-assert FGS then retry once with keep-alive present (API 34+ longevity).
            startForegroundForProjection()
            started = audioCapture.startPlaybackCapture(projection)
            Log.i(
                DIAG_TAG,
                "playbackCapture attempt=afterKeepAlive started=$started " +
                    "keepAliveCreated=$keepAliveCreated"
            )
        }
        // Hard init only — silence / zero energy must not fail Start (PlaybackCaptureStartGate).
        val hardFail = !started
        if (ProjectionCaptureStartGate.shouldFailStartAfterClaim(
                keepAliveDisplayFailed = keepAliveFailed,
                playbackCaptureStarted = started
            ) || PlaybackCaptureStartGate.shouldFailStartAfterInit(
                hardInitFailed = hardFail,
                samplesRead = 0,
                rms = 0f
            )
        ) {
            Log.e(
                DIAG_TAG,
                "EXCEPTION beginSession return-false reason=playbackCaptureFailed " +
                    "hardInit=true keepAliveCreated=$keepAliveCreated keepAliveFailed=$keepAliveFailed " +
                    "projectionNull=false captureMode=none " +
                    "usingPlaybackCapture=${audioCapture.usingPlaybackCapture} " +
                    "hasRecordAudio=${audioCapture.hasRecordAudioPermission()}"
            )
            // Already Allowed — real capture failure (not decline / not "allow sharing" / not silence / not keep-alive).
            failSessionReturnHome(getString(R.string.error_capture))
            return
        }
        // Ensure keep-alive for session longevity even if first attempt succeeded without it.
        if (!keepAliveCreated) {
            keepAliveFailed = !ensureProjectionKeepAliveDisplay(projection)
            keepAliveCreated = !keepAliveFailed
            Log.i(
                DIAG_TAG,
                "keepAlive postSuccess created=$keepAliveCreated keepAliveFailed=$keepAliveFailed"
            )
        }
        Log.i(
            DIAG_TAG,
            "beginSession ok captureMode=playback keepAliveCreated=$keepAliveCreated " +
                "projectionNull=false usingPlaybackCapture=${audioCapture.usingPlaybackCapture} " +
                "silentOk=true"
        )

        // Existing product path: overlay on launcher Home, Listening… awaiting device audio.
        showOverlay(settings.overlayX, settings.overlayY, settings.overlayWidth, settings.overlayHeight)
        audioCapture.startPump(scope)
        setSessionStatus(getString(R.string.listening), loading = true)

        val cache = ModelCache(this)
        val tier = ModelTier.fromId(settings.modelTierId)
        val modelFile = cache.fileFor(tier)
        if (!cache.isReadyForAsr(tier)) {
            failSession(getString(R.string.error_model))
            return
        }
        val preferred = InferenceEngineFactory.create(
            context = this,
            tier = tier,
            targetLanguage = settings.targetLanguage
        )
        if (preferred == null) {
            failSession(getString(R.string.error_engine_load))
            return
        }
        preferred.setTargetLanguage(settings.targetLanguage)
        val dualAllowed = preferred.canProvideDualSubtitles() && settings.dualSubtitles
        preferred.setDualSubtitles(dualAllowed)
        preferred.setPlaybackCapture(true)
        val loaded = withContext(Dispatchers.IO) { preferred.load(modelFile) }
        if (!loaded) {
            preferred.release()
            failSession(getString(R.string.error_engine_load))
            return
        }
        engine = preferred

        val mt = MlKitTranslationEngine(this)
        translation = mt
        scope.launch(Dispatchers.IO) {
            val ensure = mt.ensureModels(
                targetLanguage = settings.targetLanguage,
                extraSources = settings.passthroughLanguages
            )
            if (ensure is EnsureResult.Failed) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@CaptionOverlayService,
                        getString(R.string.translation_pack_failed, ensure.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val activeEngine = engine!!
        if (settings.saveSubtitlesToFile) {
            val path = withContext(Dispatchers.IO) {
                subtitleRecorder.startSession(
                    targetLanguage = settings.targetLanguage,
                    dualSubtitles = dualAllowed
                )
            }
            if (path == null) {
                Toast.makeText(
                    this@CaptionOverlayService,
                    getString(R.string.subtitles_save_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        setSessionStatus(getString(R.string.listening), loading = false)
        if (!hasRealCaption) {
            updateCaption(getString(R.string.listening), null)
        }

        asrJob?.cancel()
        asrJob = scope.launch(Dispatchers.IO) {
            runAsrConsumer(app, activeEngine)
        }
    }

    private suspend fun runAsrConsumer(app: CaptionActionApp, activeEngine: InferenceEngine) {
        var shownTranscribing = false
        var lastOverruns = audioCapture.overrunCount()
        var lastChunkCount = audioCapture.chunkCount()
        var quietSinceMs = 0L
        var lastCatchupStatusAt = 0L
        var lastQuietStatusAt = 0L
        while (coroutineContext.isActive) {
            val overrunsBefore = audioCapture.overrunCount()
            val depthBefore = audioCapture.queueDepth()
            // Prefer shorter Quality windows when already behind so the next pass finishes sooner.
            val behindHint = overrunsBefore > lastOverruns || depthBefore >= 3
            activeEngine.setKeepUpBehind(behindHint)
            val windowSamples = activeEngine.preferredWindowSamples()
            val pcm = audioCapture.drainToNewestWindow(windowSamples) ?: break
            val pcmMs = pcm.size * 1000L / AudioCapture.SAMPLE_RATE
            val overrunsNow = audioCapture.overrunCount()
            val depthAfter = audioCapture.queueDepth()
            val rms = audioCapture.lastRms()
            val chunksNow = audioCapture.chunkCount()
            val chunksAdvancing = chunksNow > lastChunkCount
            lastChunkCount = chunksNow
            val now = System.currentTimeMillis()

            // Sustained near-zero RMS with no pump/queue activity → honest "no signal".
            // Healthy playback RMS or advancing chunks must never look like "no device audio".
            val quietGate = 2f // playback capture only (never mic)
            val signalHealthy = rms >= quietGate ||
                chunksAdvancing ||
                depthBefore > 0 ||
                (audioCapture.usingPlaybackCapture && overrunsNow > overrunsBefore)
            if (!signalHealthy) {
                if (quietSinceMs == 0L) quietSinceMs = now
            } else {
                quietSinceMs = 0L
            }
            val quietSustained = quietSinceMs > 0L && (now - quietSinceMs) >= QUIET_STATUS_MS
            val overrunsRising = overrunsNow > lastOverruns + 2 ||
                (overrunsNow - overrunsBefore) > 0 ||
                depthBefore >= 4
            // Quality behind (overruns / backlog): catching-up — never "no audio".
            val qualityBehind = overrunsRising || depthBefore >= 3

            if (qualityBehind && now - lastCatchupStatusAt >= STATUS_THROTTLE_MS) {
                lastCatchupStatusAt = now
                withContext(Dispatchers.Main) {
                    // Do not wipe last good caption — status/spinner only.
                    setSessionStatus(getString(R.string.status_catching_up), loading = true)
                }
            } else if (
                quietSustained &&
                !hasRealCaption &&
                !signalHealthy &&
                !qualityBehind &&
                now - lastQuietStatusAt >= STATUS_THROTTLE_MS
            ) {
                lastQuietStatusAt = now
                withContext(Dispatchers.Main) {
                    // Status only — never replace Listening / last caption with no-audio text.
                    setSessionStatus(getString(R.string.status_no_audio_signal), loading = true)
                }
            } else if (!shownTranscribing && !hasRealCaption) {
                shownTranscribing = true
                withContext(Dispatchers.Main) {
                    setSessionStatus(getString(R.string.listening), loading = true)
                }
            }

            // UI honesty: last good caption held by design — if no new line for a few seconds
            // while capture is still advancing, show catching-up (do not wipe caption).
            val captionStale = hasRealCaption &&
                lastPublishedAtMs > 0L &&
                (now - lastPublishedAtMs) >= STALE_CAPTION_MS &&
                (chunksAdvancing || signalHealthy)
            if (captionStale && now - lastCatchupStatusAt >= STATUS_THROTTLE_MS) {
                lastCatchupStatusAt = now
                withContext(Dispatchers.Main) {
                    setSessionStatus(getString(R.string.status_catching_up), loading = true)
                    setCaptionDimmed(true)
                }
            }

            lastOverruns = overrunsNow

            val settingsNow = app.settings.current()
            activeEngine.setTargetLanguage(settingsNow.targetLanguage)
            val dualNow = activeEngine.canProvideDualSubtitles() && settingsNow.dualSubtitles
            activeEngine.setDualSubtitles(dualNow)
            activeEngine.setPlaybackCapture(true)
            if (overrunsRising) {
                activeEngine.discardPendingAudio()
            }

            val t0 = System.currentTimeMillis()
            // Force-flush: each drained window is a complete utterance — no re-accum across calls.
            val raw = activeEngine.transcribeWindow(pcm, AudioCapture.SAMPLE_RATE, forceFlush = true)
            val inferMs = System.currentTimeMillis() - t0
            val asrMode = activeEngine.lastAsrMode().ifBlank { "-" }
            val textPreview = raw?.text?.take(48)?.replace('\n', ' ') ?: ""
            // Junk filter logs CaptionAction filtered=true separately; null here also means "too short / quiet".
            Log.i(
                DIAG_TAG,
                "asr pcmMs=$pcmMs queueDepth=$depthBefore→$depthAfter " +
                    "overruns=$overrunsNow(+${overrunsNow - overrunsBefore}) " +
                    "rms=$rms chunks=$chunksNow advancing=$chunksAdvancing " +
                    "inferMs=$inferMs mode=$asrMode hasCaption=${raw != null} textPreview=$textPreview"
            )
            if (raw == null) {
                // Keep Listening / catching-up until a non-junk caption arrives; do not set hasRealCaption.
                // Long whisper infer or overruns ⇒ behind, not "no audio".
                if (qualityBehind || inferMs >= LONG_INFER_STATUS_MS) {
                    if (now - lastCatchupStatusAt >= STATUS_THROTTLE_MS) {
                        lastCatchupStatusAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            setSessionStatus(getString(R.string.status_catching_up), loading = true)
                        }
                    }
                } else if (!hasRealCaption && !quietSustained) {
                    withContext(Dispatchers.Main) {
                        setSessionStatus(getString(R.string.listening), loading = true)
                        updateCaption(getString(R.string.listening), null)
                    }
                }
                continue
            }
            val (primaryRaw, _) = CaptionDisplay.primaryAndSecondary(raw, dualNow)
            val norm = AsrJunkFilter.normalize(primaryRaw)
            val publishNow = System.currentTimeMillis()
            // Consecutive identical: don't re-show / re-append the same line every ~1s window.
            if (norm.isNotEmpty() && norm == lastPublishedNorm) {
                Log.i(DIAG_TAG, "suppressed identical caption norm=${norm.take(48)}")
                continue
            }
            // Near-duplicate within a short window (SenseVoice filler drift / echo).
            if (norm.isNotEmpty() &&
                lastPublishedNorm.isNotEmpty() &&
                publishNow - lastPublishedAtMs <= NEAR_DUP_WINDOW_MS &&
                AsrJunkFilter.isNearDuplicate(norm, lastPublishedNorm)
            ) {
                Log.i(DIAG_TAG, "suppressed near-dup caption norm=${norm.take(48)}")
                continue
            }
            // Reject crumbs before publish/MT — short SenseVoice fragments → nonsense EN.
            if (!AsrJunkFilter.hasEnoughContentForMt(primaryRaw)) {
                Log.i(DIAG_TAG, "suppressed short-for-mt caption norm=${norm.take(48)}")
                continue
            }
            lastPublishedNorm = norm
            lastPublishedAtMs = publishNow
            val seq = ++captionSeq
            hasRealCaption = true
            withContext(Dispatchers.Main) { setCaptionDimmed(false) }

            val mtEngine = translation
            val needsMt = mtEngine != null && likelyNeedsMt(raw, settingsNow)
            if (!needsMt || mtEngine == null) {
                publishCaption(raw, dualNow, seq, appendSubtitle = true)
                continue
            }

            // Prefer target-language primary: wait briefly for MT before painting source.
            // Do NOT cancel in-flight MT — launch concurrent; seq guard applies the latest.
            val deferred = scope.async(Dispatchers.IO) {
                try {
                    mtEngine.applyPolicy(raw, settingsNow)
                } catch (t: Throwable) {
                    Log.w(TAG, "applyPolicy failed: ${t.message}")
                    raw
                }
            }
            val quick = withTimeoutOrNull(MT_PREFER_WAIT_MS) { deferred.await() }
            if (seq != captionSeq) {
                Log.i(DIAG_TAG, "mt skipped seq=$seq latest=$captionSeq (superseded during wait)")
                continue
            }
            if (quick != null && !quick.translatedText.isNullOrBlank()) {
                publishCaption(quick, dualNow, seq, appendSubtitle = true)
                Log.i(
                    DIAG_TAG,
                    "mt applied-quick seq=$seq primary=${quick.translatedText.orEmpty().take(48)}"
                )
                // Deferred already complete; no follow-up job.
            } else {
                // Timeout or no translation yet: paint source, then swap when MT arrives.
                publishCaption(raw, dualNow = false, seq = seq, appendSubtitle = false)
                val job = scope.launch(Dispatchers.IO) {
                    val policy = quick ?: deferred.await()
                    applyMtResult(policy, dualNow, seq, allowSourceAppend = true)
                }
                trackMtJob(job)
            }
        }
    }

    private fun likelyNeedsMt(
        raw: CaptionResult,
        settings: com.hatsunama.captionaction.data.AppSettings
    ): Boolean {
        if (!raw.translatedText.isNullOrBlank()) return false
        val target = MlKitTranslationEngine.normalizeLangStatic(settings.targetLanguage)
        if (target.isEmpty()) return false
        val detected = MlKitTranslationEngine.normalizeLangStatic(raw.language)
        val passthrough = settings.passthroughLanguages
            .map { MlKitTranslationEngine.normalizeLangStatic(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (detected.isNotEmpty() && detected in passthrough) return false
        if (detected.isNotEmpty() && detected == target) return false
        return true
    }

    private fun trackMtJob(job: Job) {
        mtJobs.add(job)
        job.invokeOnCompletion { mtJobs.remove(job) }
    }

    private fun cancelMtJobs() {
        mtJobs.toList().forEach { it.cancel() }
        mtJobs.clear()
    }

    /**
     * Apply MT result when still latest [seq]. Appends subtitle once per seq (final primary).
     * @param allowSourceAppend if MT yields no translation, still append source once.
     */
    private suspend fun applyMtResult(
        policy: CaptionResult,
        dualNow: Boolean,
        seq: Long,
        allowSourceAppend: Boolean
    ) {
        if (seq != captionSeq) {
            Log.i(DIAG_TAG, "mt skipped seq=$seq latest=$captionSeq (seq mismatch)")
            return
        }
        val hasMt = !policy.translatedText.isNullOrBlank()
        if (!hasMt && !allowSourceAppend) {
            Log.i(DIAG_TAG, "mt skipped seq=$seq (no translation)")
            return
        }
        if (hasMt) {
            Log.i(
                DIAG_TAG,
                "mt applied seq=$seq primary=${policy.translatedText.orEmpty().take(48)}"
            )
        } else {
            Log.i(DIAG_TAG, "mt applied-source seq=$seq (no translation; append source)")
        }
        publishCaption(policy, dualNow, seq, appendSubtitle = true)
    }

    private suspend fun publishCaption(
        result: CaptionResult,
        dualNow: Boolean,
        seq: Long,
        appendSubtitle: Boolean
    ) {
        if (seq != captionSeq) {
            Log.i(DIAG_TAG, "publish skipped seq=$seq latest=$captionSeq")
            return
        }
        val (primaryRaw, secondaryRaw) = CaptionDisplay.primaryAndSecondary(result, dualNow)
        val primary = composer.compose(primaryRaw)
        val secondary = secondaryRaw
        if (appendSubtitle && subtitleRecorder.isRecording && subtitleAppendSeq != seq) {
            subtitleRecorder.appendCaption(primary, secondary)
            subtitleAppendSeq = seq
        }
        withContext(Dispatchers.Main) {
            if (seq != captionSeq) {
                Log.i(DIAG_TAG, "mt/ui skipped seq=$seq latest=$captionSeq (seq mismatch on main)")
                return@withContext
            }
            setSessionStatus(null, loading = false)
            updateCaption(primary, secondary)
        }
    }

    /** Fail session: Toast, tear down overlay/FGS, return to Home (main menu). Never starts mic. */
    private fun failSession(message: String) {
        LiveCaptionStarter.endStartHandoff()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val home = Intent(this, HomeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(home)
        } catch (_: Exception) {
        }
        stopSelfSafe()
    }

    private fun failSessionReturnHome(message: String) = failSession(message)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun showOverlay(x: Int, y: Int, w: Int, h: Int) {
        if (overlayView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_caption, null)
        val density = resources.displayMetrics.density
        val minW = (160 * density).toInt()
        val minH = (180 * density).toInt()
        val params = WindowManager.LayoutParams(
            max(w, minW),
            max(h, minH),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        applyFont(view, params.height)
        wireOverlayTouch(view, params, minW, minH)
        view.findViewById<View>(R.id.btnStopDot).setOnClickListener { stopAndReturnHome() }
        windowManager.addView(view, params)
        overlayView = view
        layoutParams = params
    }

    private fun stopAndReturnHome() {
        val home = Intent(this, HomeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(home)
        stopSelfSafe()
    }

    private fun wireOverlayTouch(
        view: View,
        params: WindowManager.LayoutParams,
        minW: Int,
        minH: Int
    ) {
        val handle = view.findViewById<View>(R.id.resizeHandle)
        var mode = TouchMode.NONE
        var lastX = 0f
        var lastY = 0f

        val listener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    val onHandle = v.id == R.id.resizeHandle || isNearHandle(view, event)
                    mode = if (onHandle) TouchMode.RESIZE else TouchMode.MOVE
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    lastX = event.rawX
                    lastY = event.rawY
                    when (mode) {
                        TouchMode.MOVE -> {
                            params.x += dx
                            params.y += dy
                        }
                        TouchMode.RESIZE -> {
                            val screenW = resources.displayMetrics.widthPixels
                            val screenH = resources.displayMetrics.heightPixels
                            params.width = min(screenW, max(minW, params.width + dx))
                            val maxH = (screenH * OVERLAY_MAX_HEIGHT_FRACTION).toInt()
                            params.height = min(maxH, max(minH, params.height + dy))
                            applyFont(view, params.height)
                        }
                        else -> {}
                    }
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = TouchMode.NONE
                    scope.launch {
                        (application as CaptionActionApp).settings.update {
                            it.copy(
                                overlayX = params.x,
                                overlayY = params.y,
                                overlayWidth = params.width,
                                overlayHeight = params.height
                            )
                        }
                    }
                    true
                }
                else -> false
            }
        }
        // Wire bubble + caption TextViews so drag isn't lost to children.
        // Resize stays on purple handle (or near-handle hit test). Stop-dot is a
        // sibling with its own click listener — do not attach MOVE here.
        fun prepCaptionText(tv: TextView) {
            tv.isClickable = false
            tv.isFocusable = false
            tv.isLongClickable = false
            tv.isFocusableInTouchMode = false
            tv.movementMethod = null
            tv.setHorizontallyScrolling(false)
            tv.setOnTouchListener(listener)
        }
        view.findViewById<View>(R.id.captionBubble).setOnTouchListener(listener)
        prepCaptionText(view.findViewById(R.id.captionPrimary))
        prepCaptionText(view.findViewById(R.id.captionSecondary))
        view.findViewById<TextView>(R.id.captionStatus)?.let { status ->
            status.isClickable = false
            status.isFocusable = false
            status.setOnTouchListener(listener)
        }
        handle.setOnTouchListener(listener)
    }

    private fun isNearHandle(view: View, event: MotionEvent): Boolean {
        val handle = view.findViewById<View>(R.id.resizeHandle) ?: return false
        val loc = IntArray(2)
        handle.getLocationOnScreen(loc)
        val hx = loc[0] + handle.width / 2f
        val hy = loc[1] + handle.height / 2f
        val slop = 64f * resources.displayMetrics.density
        val dx = event.rawX - hx
        val dy = event.rawY - hy
        return dx * dx + dy * dy <= slop * slop
    }

    private enum class TouchMode { NONE, MOVE, RESIZE }

    private fun applyFont(view: View, heightPx: Int) {
        val primary = view.findViewById<TextView>(R.id.captionPrimary)
        val secondary = view.findViewById<TextView>(R.id.captionSecondary)
        val tf = when (fontIndex) {
            1 -> Typeface.SANS_SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT_BOLD
        }
        primary.typeface = tf
        secondary.typeface = tf
        val density = resources.displayMetrics.density
        // Prefer smaller type + wrap so full captions fit (no ScrollingMovementMethod — drag).
        // Flatter height→sp scale so auto-grow adds lines, not giant type.
        val sp = ((heightPx / density) / 22f).coerceIn(10f, 16f)
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.85f)
        // Primary must never ellipsize — full caption wraps inside the bubble.
        primary.maxLines = 32
        primary.ellipsize = null
        primary.setHorizontallyScrolling(false)
        secondary.maxLines = 16
        secondary.ellipsize = null
    }

    private fun setSessionStatus(status: String?, loading: Boolean) {
        val view = overlayView ?: return
        view.findViewById<ProgressBar>(R.id.captionLoading).visibility =
            if (loading) View.VISIBLE else View.GONE
        val statusView = view.findViewById<TextView>(R.id.captionStatus)
        if (status.isNullOrBlank()) {
            statusView.visibility = View.GONE
        } else {
            statusView.visibility = View.VISIBLE
            statusView.text = status
        }
    }

    private fun setCaptionDimmed(dimmed: Boolean) {
        val view = overlayView ?: return
        val alpha = if (dimmed) 0.65f else 1f
        view.findViewById<TextView>(R.id.captionPrimary).alpha = alpha
        view.findViewById<TextView>(R.id.captionSecondary).alpha = alpha
    }

    private fun updateCaption(primary: String, secondary: String?) {
        val view = overlayView ?: return
        val primaryView = view.findViewById<TextView>(R.id.captionPrimary)
        primaryView.text = primary
        // Do not use ScrollingMovementMethod — it consumes touch and blocks drag.
        // Full sentences wrap (no ellipsize); bubble auto-grows up to screen cap.
        primaryView.movementMethod = null
        primaryView.setHorizontallyScrolling(false)
        primaryView.ellipsize = null
        val sec = view.findViewById<TextView>(R.id.captionSecondary)
        if (secondary.isNullOrBlank() || secondary == primary) {
            sec.visibility = View.GONE
        } else {
            sec.visibility = View.VISIBLE
            sec.text = secondary
            sec.movementMethod = null
            sec.ellipsize = null
        }
        autoGrowOverlayToContent(view)
    }

    /**
     * Grow overlay height to fit wrapped caption text, capped at ~48% screen.
     * Only grows (never shrinks) so user drag-resize down sticks. Drag preserved.
     */
    private fun autoGrowOverlayToContent(view: View) {
        if (layoutParams == null) return
        // Skip placeholder "Listening…" style short status lines.
        val primaryView = view.findViewById<TextView>(R.id.captionPrimary) ?: return
        val text = primaryView.text?.toString().orEmpty()
        if (text.length < 8) return
        view.post {
            val p = layoutParams ?: return@post
            val density = resources.displayMetrics.density
            val screenH = resources.displayMetrics.heightPixels
            val maxH = (screenH * OVERLAY_MAX_HEIGHT_FRACTION).toInt()
            val minH = (180 * density).toInt()
            val widthSpec = View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            view.measure(widthSpec, heightSpec)
            val needed = view.measuredHeight.coerceIn(minH, maxH)
            if (needed > p.height + (8 * density).toInt()) {
                p.height = needed
                applyFont(view, p.height)
                try {
                    windowManager.updateViewLayout(view, p)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptionOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CaptionActionApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.stop_live), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun removeOverlayViews() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        layoutParams = null
    }

    /**
     * Tiny VirtualDisplay so MediaProjection stays valid for AudioPlaybackCapture.
     * Best-effort only — callers must not fail Start if this returns false
     * (0.3.8 regression). Android 14+ prefers a display; silence is still fine.
     */
    private fun ensureProjectionKeepAliveDisplay(projection: MediaProjection): Boolean {
        if (projectionVirtualDisplay != null) {
            Log.i(DIAG_TAG, "keepAlive created=true alreadyPresent=true")
            return true
        }
        return try {
            val density = resources.displayMetrics.densityDpi.coerceAtLeast(1)
            val reader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2)
            // Flag 0 (not AUTO_MIRROR): audio-only keep-alive. 0.3.9 AUTO_MIRROR
            // coincided with Seeker hardInit=true while VD still created successfully.
            val display = projection.createVirtualDisplay(
                "caption-action-audio-keepalive",
                2,
                2,
                density,
                0,
                reader.surface,
                null,
                null
            )
            if (display == null) {
                Log.e(
                    DIAG_TAG,
                    "EXCEPTION ensureProjectionKeepAliveDisplay return-false " +
                        "reason=createVirtualDisplay_null keepAliveCreated=false density=$density"
                )
                reader.close()
                return false
            }
            projectionImageReader = reader
            projectionVirtualDisplay = display
            Log.i(
                DIAG_TAG,
                "keepAlive created=true VirtualDisplay=ok density=$density"
            )
            true
        } catch (t: Exception) {
            Log.e(
                DIAG_TAG,
                "EXCEPTION ensureProjectionKeepAliveDisplay catch keepAliveCreated=false",
                t
            )
            false
        }
    }

    private fun releaseKeepAliveDisplay() {
        try {
            projectionVirtualDisplay?.release()
        } catch (_: Exception) {
        }
        projectionVirtualDisplay = null
        try {
            projectionImageReader?.close()
        } catch (_: Exception) {
        }
        projectionImageReader = null
    }

    private fun releaseProjection() {
        releaseKeepAliveDisplay()
        val proj = mediaProjection
        val cb = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (proj != null) {
            try {
                if (cb != null) proj.unregisterCallback(cb)
            } catch (_: Exception) {
            }
            try {
                proj.stop()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopSelfSafe() {
        if (tearingDown) return
        tearingDown = true
        LiveCaptionStarter.endStartHandoff()
        asrJob?.cancel()
        cancelMtJobs()
        audioCapture.stop()
        engine?.release()
        engine = null
        translation?.release()
        translation = null
        releaseProjection()
        composer.reset()
        lastPublishedNorm = ""
        lastPublishedAtMs = 0L
        subtitleAppendSeq = -1L
        captionSeq = 0L
        hasRealCaption = false
        val savedPath = try {
            subtitleRecorder.stopSession()
        } catch (_: Exception) {
            null
        }
        removeOverlayViews()
        if (savedPath != null) {
            Toast.makeText(
                this,
                getString(R.string.subtitles_saved, savedPath),
                Toast.LENGTH_LONG
            ).show()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        asrJob?.cancel()
        cancelMtJobs()
        audioCapture.stop()
        engine?.release()
        engine = null
        translation?.release()
        translation = null
        releaseProjection()
        try { subtitleRecorder.discard() } catch (_: Exception) {}
        removeOverlayViews()
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptionOverlayService"
        private const val DIAG_TAG = "CaptionAction"
        private const val QUIET_STATUS_MS = 4_000L
        private const val STATUS_THROTTLE_MS = 2_500L
        /** Prefer waiting this long for MT before painting source (EN/target primary). */
        private const val MT_PREFER_WAIT_MS = 850L
        /** Auto-grow overlay up to this fraction of screen height (drag-safe; no scroll). */
        private const val OVERLAY_MAX_HEIGHT_FRACTION = 0.48f
        /** Whisper/Quality long infer → show catching-up, not no-audio. */
        private const val LONG_INFER_STATUS_MS = 3_000L
        /** Suppress near-duplicate captions inside this window (ms). */
        private const val NEAR_DUP_WINDOW_MS = 2_500L
        /** No new caption while capture advancing → catching-up status (keep last line). */
        private const val STALE_CAPTION_MS = 4_500L
        const val ACTION_START = "com.hatsunama.captionaction.START"
        const val ACTION_STOP = "com.hatsunama.captionaction.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: CaptionOverlayService? = null
            private set

        /**
         * Start a Live/Quality session with MediaProjection extras only.
         * Callers must not invoke this without a granted projection result —
         * there is no microphone start path.
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            require(resultCode != 0) { "MediaProjection result required (device audio only)" }
            val i = Intent(context, CaptionOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptionOverlayService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
