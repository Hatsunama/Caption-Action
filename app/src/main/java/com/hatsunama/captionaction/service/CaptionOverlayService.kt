package com.hatsunama.captionaction.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class CaptionOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var asrJob: Job? = null
    private var mtJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var closeFabView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var audioCapture: AudioCapture
    private val composer = SubtitleComposer()
    private var translation: MlKitTranslationEngine? = null
    private lateinit var subtitleRecorder: SubtitleFileRecorder
    private var engine: InferenceEngine? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile private var hasRealCaption = false
    @Volatile private var tearingDown = false
    private var fontIndex = 0
    private var captionSeq = 0L

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
                startForeground(NOTIF_ID, buildNotification())
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val projection = claimProjectionSync(resultCode, data)
                scope.launch { beginSession(projection, wantedProjection = resultCode != 0 && data != null) }
            }
        }
        return START_STICKY
    }

    private fun claimProjectionSync(resultCode: Int, data: Intent?): MediaProjection? {
        if (resultCode == 0 || data == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data) ?: return null
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
            projection.registerCallback(cb, Handler(Looper.getMainLooper()))
            projectionCallback = cb
            mediaProjection = projection
            projection
        } catch (t: Exception) {
            Log.e(TAG, "claimProjection failed", t)
            null
        }
    }

    private suspend fun beginSession(projection: MediaProjection?, wantedProjection: Boolean) {
        val app = application as CaptionActionApp
        val settings = app.settings.current()
        fontIndex = settings.fontIndex
        showOverlay(settings.overlayX, settings.overlayY, settings.overlayWidth, settings.overlayHeight)
        showCloseFab()
        setSessionStatus(getString(R.string.loading_engine), loading = true)

        var started = false
        if (projection != null) {
            started = audioCapture.startPlaybackCapture(projection)
        }
        if (!started) {
            if (wantedProjection || projection != null) {
                Toast.makeText(
                    this,
                    getString(R.string.permission_projection_declined_mic),
                    Toast.LENGTH_LONG
                ).show()
            }
            started = audioCapture.startMic()
        }
        if (!started) {
            failSession(getString(R.string.error_capture))
            return
        }

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
        preferred.setPlaybackCapture(audioCapture.usingPlaybackCapture)
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
        var quietSinceMs = 0L
        var lastCatchupStatusAt = 0L
        var lastQuietStatusAt = 0L
        while (coroutineContext.isActive) {
            val overrunsBefore = audioCapture.overrunCount()
            val depthBefore = audioCapture.queueDepth()
            val pcm = audioCapture.drainToNewestWindow() ?: break
            val pcmMs = pcm.size * 1000L / AudioCapture.SAMPLE_RATE
            val overrunsNow = audioCapture.overrunCount()
            val depthAfter = audioCapture.queueDepth()
            val rms = audioCapture.lastRms()
            val now = System.currentTimeMillis()

            // Sustained near-zero RMS → honest "no signal" (wrong screen / mute mix).
            val quietGate = if (audioCapture.usingPlaybackCapture) 2f else 40f
            if (rms < quietGate) {
                if (quietSinceMs == 0L) quietSinceMs = now
            } else {
                quietSinceMs = 0L
            }
            val quietSustained = quietSinceMs > 0L && (now - quietSinceMs) >= QUIET_STATUS_MS
            val overrunsRising = overrunsNow > lastOverruns + 2 ||
                (overrunsNow - overrunsBefore) > 0 ||
                depthBefore >= 4

            if (overrunsRising && now - lastCatchupStatusAt >= STATUS_THROTTLE_MS) {
                lastCatchupStatusAt = now
                withContext(Dispatchers.Main) {
                    // Do not wipe last good caption — status/spinner only.
                    setSessionStatus(getString(R.string.status_catching_up), loading = true)
                }
            } else if (quietSustained && !hasRealCaption && now - lastQuietStatusAt >= STATUS_THROTTLE_MS) {
                lastQuietStatusAt = now
                withContext(Dispatchers.Main) {
                    setSessionStatus(getString(R.string.status_no_audio_signal), loading = true)
                    updateCaption(getString(R.string.status_no_audio_signal), null)
                }
            } else if (!shownTranscribing && !hasRealCaption) {
                shownTranscribing = true
                withContext(Dispatchers.Main) {
                    setSessionStatus(getString(R.string.listening), loading = true)
                }
            }

            lastOverruns = overrunsNow

            val settingsNow = app.settings.current()
            activeEngine.setTargetLanguage(settingsNow.targetLanguage)
            val dualNow = activeEngine.canProvideDualSubtitles() && settingsNow.dualSubtitles
            activeEngine.setDualSubtitles(dualNow)
            activeEngine.setPlaybackCapture(audioCapture.usingPlaybackCapture)
            if (overrunsRising) {
                activeEngine.discardPendingAudio()
            }

            val t0 = System.currentTimeMillis()
            val raw = activeEngine.transcribe(pcm, AudioCapture.SAMPLE_RATE)
            val inferMs = System.currentTimeMillis() - t0
            val textPreview = raw?.text?.take(48)?.replace('\n', ' ') ?: ""
            // Junk filter logs CaptionAction filtered=true separately; null here also means "not flushed yet".
            Log.i(
                DIAG_TAG,
                "asr pcmMs=$pcmMs queueDepth=$depthBefore→$depthAfter " +
                    "overruns=$overrunsNow(+${overrunsNow - overrunsBefore}) " +
                    "rms=$rms inferMs=$inferMs hasCaption=${raw != null} textPreview=$textPreview"
            )
            if (raw == null) {
                // Keep Listening until a non-junk caption arrives; do not set hasRealCaption.
                if (!hasRealCaption && !quietSustained && !overrunsRising) {
                    withContext(Dispatchers.Main) {
                        setSessionStatus(getString(R.string.listening), loading = true)
                        updateCaption(getString(R.string.listening), null)
                    }
                }
                continue
            }
            val seq = ++captionSeq
            hasRealCaption = true
            val (primaryRaw, secondaryRaw) = CaptionDisplay.primaryAndSecondary(raw, dualNow)
            val primary = composer.compose(primaryRaw)
            val secondary = secondaryRaw
            if (subtitleRecorder.isRecording) {
                subtitleRecorder.appendCaption(primary, secondary)
            }
            withContext(Dispatchers.Main) {
                setSessionStatus(null, loading = false)
                updateCaption(primary, secondary)
            }

            val mtEngine = translation ?: continue
            mtJob?.cancel()
            mtJob = scope.launch(Dispatchers.IO) {
                enrichWithMt(mtEngine, raw, settingsNow, dualNow, seq)
            }
        }
    }

    private suspend fun enrichWithMt(
        mtEngine: MlKitTranslationEngine,
        raw: CaptionResult,
        settings: com.hatsunama.captionaction.data.AppSettings,
        dualNow: Boolean,
        seq: Long
    ) {
        val policy = try {
            mtEngine.applyPolicy(raw, settings)
        } catch (t: Throwable) {
            Log.w(TAG, "applyPolicy failed: ${t.message}")
            return
        }
        if (seq != captionSeq) return
        if (policy.translatedText.isNullOrBlank()) return
        val (primaryRaw, secondaryRaw) = CaptionDisplay.primaryAndSecondary(policy, dualNow)
        val primary = composer.compose(primaryRaw)
        val secondary = secondaryRaw
        if (subtitleRecorder.isRecording) {
            subtitleRecorder.appendCaption(primary, secondary)
        }
        withContext(Dispatchers.Main) {
            if (seq != captionSeq) return@withContext
            updateCaption(primary, secondary)
        }
    }

    private fun failSession(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopSelfSafe()
    }

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
        val minH = (100 * density).toInt()
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

    private fun showCloseFab() {
        if (closeFabView != null) return
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val fab = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_close_fab)
            contentDescription = getString(R.string.close_overlay)
            setOnClickListener { stopAndReturnHome() }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (24 * density).toInt()
        }
        windowManager.addView(fab, params)
        closeFabView = fab
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
                            params.height = min(screenH / 2, max(minH, params.height + dy))
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
        view.findViewById<View>(R.id.captionBubble).setOnTouchListener(listener)
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
        val sp = ((heightPx / density) / 7.5f).coerceIn(14f, 40f)
        primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.78f)
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

    private fun updateCaption(primary: String, secondary: String?) {
        val view = overlayView ?: return
        view.findViewById<TextView>(R.id.captionPrimary).text = primary
        val sec = view.findViewById<TextView>(R.id.captionSecondary)
        if (secondary.isNullOrBlank() || secondary == primary) {
            sec.visibility = View.GONE
        } else {
            sec.visibility = View.VISIBLE
            sec.text = secondary
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
        closeFabView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        closeFabView = null
        layoutParams = null
    }

    private fun releaseProjection() {
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
        asrJob?.cancel()
        mtJob?.cancel()
        audioCapture.stop()
        engine?.release()
        engine = null
        translation?.release()
        translation = null
        releaseProjection()
        composer.reset()
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
        mtJob?.cancel()
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
        const val ACTION_START = "com.hatsunama.captionaction.START"
        const val ACTION_STOP = "com.hatsunama.captionaction.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: CaptionOverlayService? = null
            private set

        fun start(context: Context, resultCode: Int = 0, data: Intent? = null) {
            val i = Intent(context, CaptionOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(EXTRA_RESULT_DATA, data)
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
