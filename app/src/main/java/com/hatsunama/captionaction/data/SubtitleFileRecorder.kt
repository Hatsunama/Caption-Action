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

/**
 * Appends live caption lines to a plain UTF-8 .txt file in app-specific
 * external Documents storage (no extra storage permissions on minSdk 26 / target 34).
 *
 * One file per caption session. Thread-safe for service coroutine writers.
 */
class SubtitleFileRecorder(private val context: Context) {

    private val lock = ReentrantLock()
    private var writer: BufferedWriter? = null
    private var file: File? = null
    @Volatile private var active = false

    /** Absolute path of the current session file, or null if not recording. */
    val currentPath: String?
        get() = lock.withLock { file?.absolutePath }

    val isRecording: Boolean
        get() = active

    /**
     * Opens a new timestamped session file and writes a short header.
     * @return absolute path on success, or null on failure (caller should toast).
     */
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

    /**
     * Appends one displayed caption line. If [secondary] is present and different,
     * writes a readable dual line: "primary | secondary".
     */
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

    /**
     * Flushes and closes the session file.
     * @return absolute path of the saved file, or null if nothing was open / empty failure.
     */
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
            // Skip transient UI status strings that are not real captions
            val s = secondary?.trim()?.takeIf { it.isNotEmpty() && it != p }
            return if (s != null) "$p | $s" else p
        }
    }
}
