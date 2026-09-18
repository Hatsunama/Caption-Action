package com.hatsunama.captionaction.service

import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads model files into app-private storage with **resumable** partials.
 *
 * Partial bytes live in `[fileName].part` under `files/models/` and persist across
 * cancel and process death. Resume uses HTTP `Range: bytes=N-` when the server
 * returns 206; if the server ignores Range and returns 200, the partial is
 * discarded and the download restarts from scratch (documented fallback).
 *
 * Only the selected tier's `.part` is touched — unrelated tiers are left alone.
 */
class ModelDownloadManager(private val cache: ModelCache) {

    data class Progress(val bytesRead: Long, val totalBytes: Long) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    class CancelledException : Exception("Download cancelled")

    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun resetCancel() {
        cancelled.set(false)
    }

    suspend fun download(
        tier: ModelTier,
        onProgress: (Progress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        cancelled.set(false)
        try {
            val dest = cache.fileFor(tier)
            val tmp = cache.partFileFor(tier)
            var existing = if (tmp.exists()) tmp.length().coerceAtLeast(0L) else 0L

            // Already complete final file — nothing to do.
            if (cache.isPresent(tier)) {
                onProgress(Progress(tier.approxBytes, tier.approxBytes))
                return@withContext Result.success(Unit)
            }

            val needBytes = (tier.approxBytes - existing).coerceAtLeast(0L)
            if (cache.freeBytes() < needBytes + 5L * 1024 * 1024) {
                return@withContext Result.failure(StorageException("Not enough storage"))
            }

            // Immediate UI feedback before network I/O (resume or fresh).
            onProgress(Progress(existing, tier.approxBytes.coerceAtLeast(1L)))

            val conn = (URL(tier.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "CaptionAction/0.1 (local; user-initiated)")
                if (existing > 0L) {
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }
            conn.connect()
            val code = conn.responseCode

            val append: Boolean
            val total: Long
            when {
                // Resume accepted
                code == HttpURLConnection.HTTP_PARTIAL && existing > 0L -> {
                    append = true
                    val contentLen = conn.contentLengthLong
                    total = if (contentLen > 0L) existing + contentLen else tier.approxBytes
                }
                // Full body (fresh start, or server ignored Range)
                code in 200..299 -> {
                    if (existing > 0L) {
                        // Fallback: server does not honor Range — restart from scratch.
                        tmp.delete()
                        existing = 0L
                    }
                    append = false
                    val contentLen = conn.contentLengthLong
                    total = if (contentLen > 0L) contentLen else tier.approxBytes
                }
                else -> {
                    conn.disconnect()
                    return@withContext Result.failure(IllegalStateException("HTTP $code"))
                }
            }

            onProgress(Progress(existing, total))

            try {
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tmp, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = existing
                        while (true) {
                            if (cancelled.get()) {
                                out.fd.sync()
                                throw CancelledException()
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            onProgress(Progress(read, total))
                        }
                        out.fd.sync()
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (cancelled.get()) {
                // Keep .part for quick resume — do not delete.
                return@withContext Result.failure(CancelledException())
            }

            // Validate size before promoting to final path.
            val finalLen = tmp.length()
            val minReady = ModelCache.minReadyBytes(tier)
            if (finalLen < minReady) {
                // Incomplete/corrupt — keep part for another resume attempt.
                return@withContext Result.failure(
                    IllegalStateException("Download incomplete (${finalLen} bytes)")
                )
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            // Final file must still pass isPresent; if not, remove junk.
            if (!cache.isPresent(tier)) {
                dest.delete()
                return@withContext Result.failure(
                    IllegalStateException("Downloaded file failed size validation")
                )
            }
            Result.success(Unit)
        } catch (c: CancelledException) {
            // Partial retained in .part for this tier only.
            Result.failure(c)
        } catch (t: Throwable) {
            // Network/IO failure: keep .part so the next attempt can resume.
            Result.failure(t)
        }
    }

    class StorageException(message: String) : Exception(message)
}
