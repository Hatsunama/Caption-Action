package com.hatsunama.captionaction.captureprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object ProbeState {
    @Volatile var status = "Not started. No audio captured."
    @Volatile var running = false
}

class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val update = object : Runnable {
        override fun run() { status.text = ProbeState.status; handler.postDelayed(this, 250) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        box.addView(TextView(this).apply { text = "Capture-component test only. No ASR, microphone, saved audio, network or screen images.\n\nPause every other audio source. Media volume must already be ZERO. This app never changes volume. Leave volume untouched throughout the 18-second test. External/fixed-volume routes are blocked. Route/volume changes abort and close this probe. An independent 30-second deadline closes it if native code hangs.\n\nOwn-UID platform capture probe. This does not test production Live or ASR. Android still requires audio permission and screen/audio sharing consent. Only synthetic tone/silence and aggregate statistics are used.\n" })
        box.addView(Button(this).apply { text = "Begin muted capture test"; setOnClickListener { begin() } })
        box.addView(Button(this).apply { text = "Stop now"; setOnClickListener { stopService(Intent(this@ProbeActivity, ProbeService::class.java)) } })
        status = TextView(this); box.addView(status)
        setContentView(ScrollView(this).apply { addView(box) })
    }
    override fun onResume() { super.onResume(); handler.post(update) }
    override fun onPause() { handler.removeCallbacks(update); super.onPause() }
    private fun begin() {
        if (ProbeState.running) return
        if (getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
            ProbeState.status = "BLOCKED: set media volume to zero yourself before starting. No capture started."; return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 2)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) ProbeState.status = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            "Audio permission granted. Press Begin for system sharing consent." else "Permission declined. No capture."
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2) return
        if (resultCode != RESULT_OK || data == null) { ProbeState.status = "Sharing declined. No capture."; return }
        if (getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
            ProbeState.status = "BLOCKED: volume changed. No capture."; return
        }
        startForegroundService(Intent(this, ProbeService::class.java).putExtra("code", resultCode).putExtra("consent", data))
    }
}
