package com.hatsunama.captionaction.service

import android.content.Context
import com.hatsunama.captionaction.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** These inputs and assertions run unchanged against archived pre-repair and repaired sources. */
class IntegrityNegativeTest {
    companion object {
        const val EXACT = 32_152_673L
        val TIER = ModelTier.BALANCED
        @Volatile var script: (Int) -> Response = { Response(500) }
        val requests = CopyOnWriteArrayList<HttpURLConnection>()
        @JvmStatic @BeforeClass fun install() {
            URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(u: URL): URLConnection {
                    val r = script(requests.size)
                    return object : HttpURLConnection(u) {
                        override fun connect() {}
                        override fun disconnect() { r.disconnected?.countDown() }
                        override fun usingProxy() = false
                        override fun getResponseCode() = r.code
                        override fun getContentLengthLong() = r.length
                        override fun getHeaderField(name: String?): String? = when(name) {
                            "Content-Range" -> r.range
                            "Content-Encoding" -> r.encoding
                            else -> null
                        }
                        override fun getInputStream() = r.stream ?: Zeros(r.bytes)
                    }.also { requests.add(it) }
                }
            } }
        }
    }
    data class Response(val code: Int, val bytes: Long = 0, val length: Long = -1, val range: String? = null,
        val encoding: String? = null, val stream: InputStream? = null, val disconnected: CountDownLatch? = null)
    class Zeros(var remaining: Long): InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(remaining, len.toLong()).toInt()
            b.fill(0, off, off+n); remaining -= n; return n
        }
    }
    private lateinit var dir: File
    private lateinit var cache: ModelCache
    @Before fun setup() { requests.clear(); script = { Response(500) }; dir = Files.createTempDirectory("n5-negative").toFile(); cache = ModelCache(Context(dir)) }
    @After fun cleanup() { dir.deleteRecursively() }
    private fun sparse(f: File, n: Long) { RandomAccessFile(f,"rw").use { it.setLength(n) } }
    private suspend fun rejects() {
        assertTrue("Must reject invalid artifact", ModelDownloadManager(cache).download(TIER) {}.isFailure)
        assertFalse("Must not publish invalid final", cache.fileFor(TIER).isFile)
        assertFalse(cache.isReadyForAsr(TIER))
    }
    @Test fun thresholdFinalNeverReady() { sparse(cache.fileFor(TIER), 30_000_000); assertFalse(cache.isReadyForAsr(TIER)) }
    @Test fun exactWrongFinalNeverReady() { sparse(cache.fileFor(TIER), EXACT); assertFalse(cache.isReadyForAsr(TIER)) }
    @Test fun invalidFinalDoesNotBypassRecovery() = runBlocking {
        sparse(cache.fileFor(TIER), EXACT)
        assertTrue(ModelDownloadManager(cache).download(TIER) {}.isFailure)
        assertEquals(1, requests.size)
    }
    @Test fun prematureChunkedEofAboveOldThreshold() = runBlocking { script = { Response(200, 30_000_000) }; rejects() }
    @Test fun exactWrongBody() = runBlocking { script = { Response(200, EXACT, EXACT) }; rejects() }
    private suspend fun rangeCase(range: String?) {
        sparse(cache.partFileFor(TIER), 7)
        script = { Response(206, EXACT-7, EXACT-7, range) }; rejects()
    }
    @Test fun missingRange() = runBlocking { rangeCase(null) }
    @Test fun wrongRangeStart() = runBlocking { rangeCase("bytes 8-${EXACT-1}/$EXACT") }
    @Test fun wrongRangeTotal() = runBlocking { rangeCase("bytes 7-${EXACT-1}/${EXACT+1}") }
    @Test fun malformedRange() = runBlocking { rangeCase("garbage") }
    @Test fun impossibleRangeEnd() = runBlocking { rangeCase("bytes 7-$EXACT/$EXACT") }
    @Test fun fresh206Rejected() = runBlocking { script = { Response(206, EXACT, EXACT, "bytes 0-${EXACT-1}/$EXACT") }; rejects() }
    @Test fun other2xxRejected() = runBlocking { script = { Response(204, EXACT, EXACT) }; rejects() }
    @Test fun sameLengthMixedEntityRejected() = runBlocking { rangeCase("bytes 7-${EXACT-1}/$EXACT") }
    @Test fun knownWrongFullPartialResetsBeforeRequest() = runBlocking {
        sparse(cache.partFileFor(TIER), EXACT); script = { Response(500) }
        assertTrue(ModelDownloadManager(cache).download(TIER) {}.isFailure)
        assertNull(requests.single().getRequestProperty("Range"))
        assertEquals(0, cache.partialBytes(TIER))
    }
    @Test fun oversizedPartialResetsBeforeRequest() = runBlocking {
        sparse(cache.partFileFor(TIER), EXACT+1); script = { Response(500) }
        assertTrue(ModelDownloadManager(cache).download(TIER) {}.isFailure)
        assertNull(requests.single().getRequestProperty("Range"))
    }
    @Test fun response416HasOneBoundedReset() = runBlocking {
        sparse(cache.partFileFor(TIER),7); script = { Response(416) }
        assertTrue(ModelDownloadManager(cache).download(TIER) {}.isFailure)
        assertEquals(2, requests.size); assertEquals("bytes=7-", requests[0].getRequestProperty("Range")); assertNull(requests[1].getRequestProperty("Range"))
    }
    @Test fun cancelBeforeStartDoesNotOpenNetwork() = runBlocking {
        val manager = ModelDownloadManager(cache); manager.cancel()
        assertTrue(manager.download(TIER) {}.isFailure); assertEquals(0, requests.size)
    }
    @Test fun writersRemainExclusiveAndQueuedCancellationCannotReset() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        script = { Response(200, stream = object: InputStream() { override fun read(): Int {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); return -1
        } }) }
        val first = launch(Dispatchers.Default) { ModelDownloadManager(cache).download(TIER) {} }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val secondManager = ModelDownloadManager(cache)
        val waiting = CompletableDeferred<Unit>()
        val second = async(Dispatchers.Default) { waiting.complete(Unit); secondManager.download(TIER) {} }
        try { waiting.await(); secondManager.cancel(); assertEquals(1,requests.size) }
        finally { release.countDown(); first.join() }
        assertTrue(second.await().isFailure); assertEquals(1,requests.size)
        assertFalse(cache.isReadyForAsr(TIER))
    }
}
