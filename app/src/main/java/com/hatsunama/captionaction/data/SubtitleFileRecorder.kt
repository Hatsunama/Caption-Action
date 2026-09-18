package com.hatsunama.captionaction.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Session .txt captions under app Documents/captions. Thread-safe. */
class SubtitleFileRecorder(private val context: Context) {

    private val lock = ReentrantLock()
    private var writer: BufferedWriter? = null
    private var file: File? = null
    @Volatile private var active = false

    val currentPath: String?
        get() = lock.withLock { file?.absolutePath }

    val isRecording: Boolean
        get() = active

    /** @return absolute path on success, or null on failure. */
    fun startSession(targetLanguage: String, dualSubtitles: Boolean): String? = lock.withLock {
        closeUnlocked()
        return try {
            val dir = captionsDir()
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Could not create captions dir: ${dir.absolutePath}")
                return null
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(dir, "caption-action-$stamp.txt")
            val bw = BufferedWriter(
                OutputStreamWriter(FileOutputStream(out, false), StandardCharsets.UTF_8)
            )
            bw.write("# Caption Action")
            bw.newLine()
            bw.write("# Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            bw.newLine()
            bw.write("# Target language: $targetLanguage")
            bw.newLine()
            bw.write("# Dual subtitles: ${if (dualSubtitles) "on" else "off"}")
            bw.newLine()
            bw.write("# Local only — no upload")
            bw.newLine()
            bw.newLine()
            bw.flush()
            writer = bw
            file = out
            active = true
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "startSession failed", e)
            writer = null
            file = null
            active = false
            null
        }
    }

    fun appendCaption(primary: String, secondary: String?) {
        val line = formatLine(primary, secondary) ?: return
        lock.withLock {
            if (!active) return
            val w = writer ?: return
            try {
                w.write(line)
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                Log.e(TAG, "appendCaption failed", e)
            }
        }
    }

    /** @return saved path, or null if nothing was open. */
    fun stopSession(): String? = lock.withLock {
        val path = file?.absolutePath
        closeUnlocked()
        path
    }

    fun discard() = lock.withLock { closeUnlocked() }

    private fun closeUnlocked() {
        active = false
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close failed", e)
        }
        writer = null
        file = null
    }

    private fun captionsDir(): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val base = external ?: File(context.filesDir, "Documents")
        return File(base, "captions")
    }

    companion object {
        private const val TAG = "SubtitleFileRecorder"

        fun formatLine(primary: String, secondary: String?): String? {
            val p = primary.trim()
            if (p.isEmpty()) return null
            val s = secondary?.trim()?.takeIf { it.isNotEmpty() && it != p }
            return if (s != null) "$p | $s" else p
        }
    }
}
