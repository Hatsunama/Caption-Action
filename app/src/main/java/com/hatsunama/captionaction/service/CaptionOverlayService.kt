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
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.hatsunama.captionaction.CaptionActionApp
import com.hatsunama.captionaction.R
import com.hatsunama.captionaction.audio.AudioCapture
import com.hatsunama.captionaction.data.CaptionRingBuffer
import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.inference.InferenceEngineFactory
import com.hatsunama.captionaction.inference.PassthroughTranslationEngine
import com.hatsunama.captionaction.inference.SubtitleComposer
import com.hatsunama.captionaction.ui.live.LiveSessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptionOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pipelineJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var audioCapture: AudioCapture
    private val ringBuffer = CaptionRingBuffer()
    private val composer = SubtitleComposer()
    private val translation = PassthroughTranslationEngine()
    private val engine = InferenceEngineFactory.create()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioCapture = AudioCapture(this)
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
                scope.launch { beginSession(resultCode, data) }
            }
        }
        return START_STICKY
    }

    private suspend fun beginSession(resultCode: Int, data: Intent?) {
        val app = application as CaptionActionApp
        val settings = app.settings.current()
        showOverlay(settings.overlayX, settings.overlayY, settings.overlayWidth, settings.overlayHeight, settings.fontIndex)

        val cache = ModelCache(this)
        val tier = ModelTier.fromId(settings.modelTierId)
        val modelFile = cache.fileFor(tier)
        withContext(Dispatchers.IO) { engine.load(modelFile) }

        var started = false
        if (settings.preferPlaybackCapture && resultCode != 0 && data != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection: MediaProjection? = try {
                mpm.getMediaProjection(resultCode, data)
            } catch (_: Exception) {
                null
            }
            if (projection != null) {
                started = audioCapture.startPlaybackCapture(projection)
            }
        }
        if (!started) {
            started = audioCapture.startMic()
        }
        if (!started) {
            updateCaption(getString(R.string.error_capture), null)
            _events.emit(SessionEvent.Error(getString(R.string.error_capture)))
            return
        }

        _events.emit(
            SessionEvent.Started(
                source = if (audioCapture.usingPlaybackCapture) "playback" else "microphone",
                engine = engine.name
            )
        )

        pipelineJob?.cancel()
        pipelineJob = scope.launch(Dispatchers.IO) {
            audioCapture.readLoop(chunkSamples = 16_000) { pcm ->
                val raw = engine.transcribe(pcm, 16_000) ?: return@readLoop
                val settingsNow = app.settings.current()
                val policy = translation.applyPolicy(raw, settingsNow)
                ringBuffer.add(policy)
                val primary = composer.compose(policy.translatedText ?: policy.text)
                val secondary = if (settingsNow.dualSubtitles) policy.text else null
                withContext(Dispatchers.Main) {
                    updateCaption(primary, secondary)
                }
                _events.emit(SessionEvent.Caption(primary, secondary))
            }
        }
    }

    private fun showOverlay(x: Int, y: Int, w: Int, h: Int, fontIndex: Int) {
        if (overlayView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_caption, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            w,
            h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        applyFont(view, fontIndex)
        var dragX = 0
        var dragY = 0
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = event.rawX.toInt()
                    dragY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - dragX
                    val dy = event.rawY.toInt() - dragY
                    dragX = event.rawX.toInt()
                    dragY = event.rawY.toInt()
                    params.x += dx
                    params.y += dy
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    scope.launch {
                        (application as CaptionActionApp).settings.update {
                            it.copy(overlayX = params.x, overlayY = params.y)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(view, params)
        overlayView = view
        layoutParams = params
        updateCaption("Listening…", null)
    }

    private fun applyFont(view: View, fontIndex: Int) {
        val primary = view.findViewById<TextView>(R.id.captionPrimary)
        val secondary = view.findViewById<TextView>(R.id.captionSecondary)
        val tf = when (fontIndex) {
            1 -> Typeface.SANS_SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT_BOLD
        }
        primary.typeface = tf
        secondary.typeface = tf
        val lp = layoutParams
        if (lp != null && lp.height > 0) {
            val sp = (lp.height / 10f).coerceIn(14f, 36f)
            primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            secondary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * 0.8f)
        }
    }

    private fun updateCaption(primary: String, secondary: String?) {
        val view = overlayView ?: return
        view.findViewById<TextView>(R.id.captionPrimary).text = primary
        val sec = view.findViewById<TextView>(R.id.captionSecondary)
        if (secondary.isNullOrBlank()) {
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
            Intent(this, LiveSessionActivity::class.java),
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

    private fun stopSelfSafe() {
        pipelineJob?.cancel()
        audioCapture.stop()
        engine.release()
        ringBuffer.clear()
        composer.reset()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        scope.launch { _events.emit(SessionEvent.Stopped) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pipelineJob?.cancel()
        audioCapture.stop()
        engine.release()
        ringBuffer.clear()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    sealed class SessionEvent {
        data class Started(val source: String, val engine: String) : SessionEvent()
        data class Caption(val primary: String, val secondary: String?) : SessionEvent()
        data class Error(val message: String) : SessionEvent()
        data object Stopped : SessionEvent()
    }

    companion object {
        const val ACTION_START = "com.hatsunama.captionaction.START"
        const val ACTION_STOP = "com.hatsunama.captionaction.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 42

        @Volatile
        var instance: CaptionOverlayService? = null
            private set

        private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 32)
        val events = _events.asSharedFlow()

        fun start(context: Context, resultCode: Int = 0, data: Intent? = null) {
            val i = Intent(context, CaptionOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompatStart(context, i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptionOverlayService::class.java).setAction(ACTION_STOP)
            )
        }

        private fun ContextCompatStart(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
