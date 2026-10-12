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
        try {
            checkActive()
            val tmp = cache.partFileFor(tier)
            val total = cache.identity(tier).bytes
            if (cache.verifyLocked(tier, ::checkActive)) {
                onProgress(Progress(total, total))
                checkActive()
                return Result.success(Unit)
            }

            var existing = tmp.length()
            if (existing == total) {
                if (cache.promoteLocked(tier, ::checkActive)) {
                    onProgress(Progress(total, total))
                    checkActive()
                    return Result.success(Unit)
                }
                resetPartial(tmp)
                existing = 0
            } else if (existing > total) {
                resetPartial(tmp)
                existing = 0
            }

            var reset416 = false
            while (true) {
                checkActive()
                requireSpace(total - existing)
                onProgress(Progress(existing, total))
                val conn = (URL(tier.downloadUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "CaptionAction/0.2 (local; user-initiated)")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                try {
                    if (!control.attach { runCatching { conn.disconnect() } }) throw CancelledException()
                    checkActive()
                    conn.connect()
                    val code = conn.responseCode
                    checkActive()
                    if (code == 416 && !reset416) {
                        reset416 = true
                        resetPartial(tmp)
                        existing = 0
                        continue
                    }
                    val encoding = conn.getHeaderField("Content-Encoding")
                    check(encoding == null || encoding.equals("identity", ignoreCase = true)) { "Unexpected content encoding" }
                    val append: Boolean
                    val responseBytes: Long
                    when (code) {
                        HttpURLConnection.HTTP_OK -> {
                            append = false
                            responseBytes = total
                            existing = 0
                            requireSpace(total)
                        }
                        HttpURLConnection.HTTP_PARTIAL -> {
                            check(existing > 0) { "Unsolicited partial response" }
                            val range = parseRange(conn.getHeaderField("Content-Range"))
                            check(range != null && range.first == existing && range.third == total &&
                                range.second >= range.first && range.second < total) { "Invalid Content-Range" }
                            append = true
                            responseBytes = range.second - range.first + 1
                        }
                        else -> error("HTTP $code")
                    }
                    val declaredLength = conn.contentLengthLong
                    check(declaredLength < 0 || declaredLength == responseBytes) { "Incorrect Content-Length" }
                    checkActive()
                    BufferedInputStream(conn.inputStream).use { input ->
                        checkActive()
                        FileOutputStream(tmp, append).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                checkActive()
                                val n = input.read(buf)
                                checkActive()
                                if (n < 0) break
                                if (n == 0) continue
                                if (n.toLong() > responseBytes - received || n.toLong() > total - existing - received) {
                                    // Do not write out-of-bounds bytes. The response is known unusable.
                                    throw InvalidBodyException("Response exceeded expected bounds")
                                }
                                out.write(buf, 0, n)
                                received += n
                                onProgress(Progress(existing + received, total))
                            }
                            cache.syncLocked(out)
                            check(received == responseBytes && existing + received == total) { "Download incomplete" }
                        }
                    }
                } catch (e: InvalidBodyException) {
                    resetPartial(tmp)
                    throw e
                } finally {
                    control.detach()
                    runCatching { conn.disconnect() }
                }
                checkActive()
                if (!cache.promoteLocked(tier, ::checkActive)) {
                    // A full wrong entity cannot be resumed into the approved artifact.
                    resetPartial(tmp)
                    error("Downloaded model failed integrity verification")
                }
                checkActive()
                return Result.success(Unit)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (c: CancelledException) {
            return Result.failure(c)
        } catch (t: Exception) {
            // disconnect() may surface as IOException rather than our cancellation marker.
            return Result.failure(if (control.isCancelled) CancelledException() else t)
        } finally {
            control.detach()
        }
    }

    private suspend fun resetPartial(file: java.io.File) {
        checkActive()
        java.nio.file.Files.deleteIfExists(file.toPath())
    }

    private fun requireSpace(remaining: Long) {
        if (cache.freeBytes() < remaining + 5L * 1024 * 1024) throw StorageException("Not enough storage")
    }

    private fun parseRange(value: String?): Triple<Long, Long, Long>? {
        val parts = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(value?.trim() ?: return null) ?: return null
        return Triple(parts.groupValues[1].toLongOrNull() ?: return null,
            parts.groupValues[2].toLongOrNull() ?: return null,
            parts.groupValues[3].toLongOrNull() ?: return null)
    }

    private class InvalidBodyException(message: String) : Exception(message)

    class StorageException(message: String) : Exception(message)
}
