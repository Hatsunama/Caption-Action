package com.hatsunama.captionaction.service

import android.content.Context
import com.hatsunama.captionaction.data.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.BeforeClass
import java.io.InputStream
import java.net.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActualDownloadManagerTest {
    companion object {
        @Volatile var fixture = Fixture()
        @JvmStatic @BeforeClass fun installFakeHttps() {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL): URLConnection = fixture.connection(url)
                }
            }
        }
    }
    class Fixture {
        val opens = AtomicInteger()
        val reads = AtomicInteger()
        val secondRead = CountDownLatch(1)
        val firstRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun connection(url: URL) = object : HttpURLConnection(url) {
            init { opens.incrementAndGet() }
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = 1L
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    val n = reads.incrementAndGet()
                    if (n == 1) firstRead.countDown() else secondRead.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Harness release timeout" }
                    return -1
                }
            }
        }
    }
    @Test fun cancelBeforeActualDownloadStartsMustNotOpenNetwork() = runBlocking {
        val dir = Files.createTempDirectory("actual-model-pre-cancel").toFile()
        fixture = Fixture().also { it.release.countDown() }
        try {
            val manager = ModelDownloadManager(ModelCache(Context(dir)))
            manager.cancel()
            val result = manager.download(ModelTier.BALANCED) {}
            assertTrue(result.isFailure)
            assertEquals("Actual manager reset prior cancellation", 0, fixture.opens.get())
        } finally { dir.deleteRecursively() }
    }
    @Test fun twoActualManagersMustNotOpenConcurrentPartialWriters() = runBlocking {
        val dir = Files.createTempDirectory("actual-model-writers").toFile()
        fixture = Fixture()
        val cache = ModelCache(Context(dir))
        var first: Job? = null; var second: Job? = null
        try {
            first = launch(Dispatchers.Default) { ModelDownloadManager(cache).download(ModelTier.BALANCED) {} }
            assertTrue("First actual file writer must be blocked in input read", fixture.firstRead.await(5, TimeUnit.SECONDS))
            val secondStarted = CompletableDeferred<Unit>()
            second = launch(Dispatchers.Default) {
                secondStarted.complete(Unit)
                ModelDownloadManager(cache).download(ModelTier.BALANCED) {}
            }
            secondStarted.await()
            assertFalse("Second actual manager read with another FileOutputStream still open", fixture.secondRead.await(500, TimeUnit.MILLISECONDS))
        } finally {
            fixture.release.countDown()
            first?.join(); second?.join()
            dir.deleteRecursively()
        }
    }
}
