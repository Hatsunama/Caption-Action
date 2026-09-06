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
            if (cache.freeBytes() < tier.approxBytes + 5L * 1024 * 1024) {
                return@withContext Result.failure(StorageException("Not enough storage"))
            }
            val dest = cache.fileFor(tier)
            val tmp = dest.resolveSibling(dest.name + ".part")
            if (tmp.exists()) tmp.delete()

            val conn = (URL(tier.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "CaptionAction/0.1 (local; user-initiated)")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP ${conn.responseCode}"))
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else tier.approxBytes }
            try {
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            if (cancelled.get()) {
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
                tmp.delete()
                return@withContext Result.failure(CancelledException())
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Result.success(Unit)
        } catch (c: CancelledException) {
            Result.failure(c)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    class StorageException(message: String) : Exception(message)
}
