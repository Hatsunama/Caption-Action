package com.hatsunama.captionaction.data

import android.content.Context
import com.hatsunama.captionaction.service.ModelDownloadLocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class ModelIdentity(val bytes: Long, val sha256: String)

/** Internal filesystem boundary for deterministic failure/cancellation tests. No runtime overrides. */
internal open class ModelFileOperations {
    open fun openRead(file: File): InputStream = file.inputStream()
    open fun sync(output: java.io.FileOutputStream) { output.fd.sync() }
    open fun atomicReplace(source: File, destination: File) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}

class ModelCache internal constructor(
    context: Context,
    private val identityFor: (ModelTier) -> ModelIdentity,
    private val operations: ModelFileOperations = ModelFileOperations()
) {
    constructor(context: Context) : this(context, { ModelIdentity(it.expectedBytes, it.sha256) })

    private val root = File(context.filesDir, "models").canonicalFile.also { it.mkdirs() }
    fun fileFor(tier: ModelTier): File = File(root, tier.fileName)
    fun partFileFor(tier: ModelTier): File = File(root, tier.fileName + ".part")
    internal fun syncLocked(output: java.io.FileOutputStream) = operations.sync(output)
    internal fun identity(tier: ModelTier): ModelIdentity = identityFor(tier)

    /** Cheap metadata-only snapshot. Metadata can invalidate trust, but can never establish it. */
    fun isPresent(tier: ModelTier): Boolean {
        val file = fileFor(tier)
        val known = ready[file.path] ?: return false
        if (known.identity == identity(tier) && known.stamp == stamp(file)) return true
        ready.remove(file.path, known)
        return false
    }
    fun isReadyForAsr(tier: ModelTier): Boolean = isPresent(tier)
    fun isPartial(tier: ModelTier): Boolean = !isPresent(tier) && partialBytes(tier) > 0
    fun partialBytes(tier: ModelTier): Long = partFileFor(tier).length()
    fun freeBytes(): Long = root.usableSpace

    suspend fun deletePartial(tier: ModelTier) = withContext(Dispatchers.IO) {
        ModelDownloadLocks.withFile(fileFor(tier)) { Files.deleteIfExists(partFileFor(tier).toPath()); Unit }
    }

    /** Always read actual bytes, off Main, including legacy files and warm readiness snapshots. */
    suspend fun verify(tier: ModelTier): Boolean = withContext(Dispatchers.IO) {
        ModelDownloadLocks.withFile(fileFor(tier)) { verifyLocked(tier) }
    }

    /** Keep cooperating writers excluded through path resolution and the entire native load. */
    suspend fun <T> withVerifiedModel(tier: ModelTier, block: suspend (File) -> T): T? = withContext(Dispatchers.IO) {
        ModelDownloadLocks.withFile(fileFor(tier)) {
            if (verifyLocked(tier)) block(fileFor(tier)) else null
        }
    }

    internal suspend fun verifyLocked(tier: ModelTier, check: suspend () -> Unit = {}): Boolean {
        val file = fileFor(tier)
        // A force-check must not transiently revoke an already byte-verified snapshot.
        // Readers still validate its identity/stamp; the fresh result is authoritative here.
        val expected = identity(tier)
        val verified = verifyBytes(file, expected, check)
        if (verified == null) {
            ready.remove(file.path)
            return false
        }
        checkActive(check)
        ready[file.path] = Verified(expected, verified)
        return true
    }

    /** Only a verified partial may be atomically published; there is deliberately no copy fallback. */
    internal suspend fun promoteLocked(tier: ModelTier, check: suspend () -> Unit): Boolean {
        val part = partFileFor(tier)
        if (verifyBytes(part, identity(tier), check) == null) return false
        checkActive(check)
        // A recovered full partial may have survived a failed fsync or interruption.
        // Every promotion, including the network-free recovery path, must sync successfully.
        java.io.FileOutputStream(part, true).use { operations.sync(it) }
        checkActive(check)
        val file = fileFor(tier)
        ready.remove(file.path)
        operations.atomicReplace(part, file)
        // Verify the destination itself; never infer that arbitrary post-move bytes are trusted.
        checkActive(check)
        kotlin.check(verifyLocked(tier, check)) { "Published model failed verification" }
        return true
    }

    private suspend fun checkActive(check: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        check()
    }

    private suspend fun verifyBytes(file: File, identity: ModelIdentity, check: suspend () -> Unit): Stamp? {
        checkActive(check)
        val before = stamp(file) ?: return null
        if (before.size != identity.bytes) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            operations.openRead(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive(check)
                    val n = input.read(buffer)
                    checkActive(check)
                    if (n < 0) break
                    if (n == 0) continue
                    count += n
                    if (count > identity.bytes) return null
                    digest.update(buffer, 0, n)
                }
            }
        } catch (e: java.io.IOException) {
            checkActive(check)
            return null
        }
        checkActive(check)
        if (count != identity.bytes || before != stamp(file)) return null
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual == identity.sha256) before else null
    }

    private data class Stamp(val size: Long, val modified: FileTime, val created: FileTime, val key: Any?)
    private data class Verified(val identity: ModelIdentity, val stamp: Stamp)
    companion object {
        private val ready = ConcurrentHashMap<String, Verified>()
        private fun stamp(file: File): Stamp? = try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            if (!attrs.isRegularFile) null else Stamp(attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), attrs.fileKey())
        } catch (_: java.io.IOException) { null }

        /** Compatibility for callers displaying a required size; never a readiness predicate. */
        fun minReadyBytes(tier: ModelTier): Long = tier.expectedBytes
    }
}
