package com.hatsunama.captionaction.service

import com.hatsunama.captionaction.data.ModelCache
import com.hatsunama.captionaction.data.ModelTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable model download into app-private storage.
 * Partials live in `[fileName].part`; resume via HTTP Range when the server returns 206.
 */
class ModelDownloadManager(private val cache: ModelCache) {

    data class Progress(val bytesRead: Long, val totalBytes: Long) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    class CancelledException : Exception("Download cancelled")

    private val control = ModelDownloadControl()

    // Managers are single-attempt objects. Resume/retry creates a new manager.
    fun cancel() { control.cancel() }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (control.isCancelled) throw CancelledException()
    }

    suspend fun download(
        tier: ModelTier,
        onProgress: (Progress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        ModelDownloadLocks.withFile(cache.fileFor(tier)) {
            downloadLocked(tier, onProgress)
        }
    }

    private suspend fun downloadLocked(
        tier: ModelTier,
        onProgress: (Progress) -> Unit
    ): Result<Unit> {
        var connection: HttpURLConnection? = null
        try {
            checkActive()
            val dest = cache.fileFor(tier)
            val tmp = cache.partFileFor(tier)
            var existing = if (tmp.exists()) tmp.length().coerceAtLeast(0L) else 0L

            if (cache.isPresent(tier)) {
                onProgress(Progress(tier.approxBytes, tier.approxBytes))
                return Result.success(Unit)
            }

            val needBytes = (tier.approxBytes - existing).coerceAtLeast(0L)
            if (cache.freeBytes() < needBytes + 5L * 1024 * 1024) {
                return Result.failure(StorageException("Not enough storage"))
            }

            onProgress(Progress(existing, tier.approxBytes.coerceAtLeast(1L)))

            val conn = (URL(tier.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "CaptionAction/0.2 (local; user-initiated)")
                if (existing > 0L) {
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }
            connection = conn
            if (!control.attach { runCatching { conn.disconnect() } }) throw CancelledException()
            checkActive()
            conn.connect()
            val code = conn.responseCode
            checkActive()

            val append: Boolean
            val total: Long
            when {
                code == HttpURLConnection.HTTP_PARTIAL && existing > 0L -> {
                    append = true
                    val contentLen = conn.contentLengthLong
                    total = if (contentLen > 0L) existing + contentLen else tier.approxBytes
                }
                code in 200..299 -> {
                    if (existing > 0L) {
                        tmp.delete()
                        existing = 0L
                    }
                    append = false
                    val contentLen = conn.contentLengthLong
                    total = if (contentLen > 0L) contentLen else tier.approxBytes
                }
                else -> {
                    conn.disconnect()
                    return Result.failure(IllegalStateException("HTTP $code"))
                }
            }

            onProgress(Progress(existing, total))

            try {
                checkActive()
                BufferedInputStream(conn.inputStream).use { input ->
                    checkActive()
                    FileOutputStream(tmp, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = existing
                        while (true) {
                            checkActive()
                            val n = input.read(buf)
                            checkActive()
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

            checkActive()

            val finalLen = tmp.length()
            val minReady = ModelCache.minReadyBytes(tier)
            if (finalLen < minReady) {
                return Result.failure(
                    IllegalStateException("Download incomplete (${finalLen} bytes)")
                )
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (!cache.isPresent(tier)) {
                dest.delete()
                return Result.failure(
                    IllegalStateException("Downloaded file failed size validation")
                )
            }
            return Result.success(Unit)
        } catch (c: CancellationException) {
            throw c
        } catch (c: CancelledException) {
            return Result.failure(c)
        } catch (t: Exception) {
            // disconnect() may surface as an IOException instead of our cancellation marker.
            return Result.failure(if (control.isCancelled) CancelledException() else t)
        } finally {
            control.detach()
            connection?.let { runCatching { it.disconnect() } }
        }
    }

    class StorageException(message: String) : Exception(message)
}
