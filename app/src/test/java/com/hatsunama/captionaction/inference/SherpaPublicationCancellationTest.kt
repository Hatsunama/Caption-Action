package com.hatsunama.captionaction.inference

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Cancellation after construction but while waiting to acquire the actual engine's publication lock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SherpaPublicationCancellationTest.RecognizerBoundary::class],
    instrumentedPackages = ["com.k2fsa.sherpa.onnx"])
class SherpaPublicationCancellationTest {
    @get:Rule val temporary = TemporaryFolder()
    companion object {
        lateinit var entered: CountDownLatch
        lateinit var resume: CountDownLatch
        @Volatile var constructingThread: Thread? = null
        val releases = AtomicInteger()
    }
    @Implements(value = OfflineRecognizer::class, isInAndroidSdk = false)
    class RecognizerBoundary {
        companion object { @JvmStatic @Implementation fun __staticInitializer__() {} }
        @Implementation fun __constructor__(assets: AssetManager?, config: OfflineRecognizerConfig) {
            constructingThread = Thread.currentThread()
            entered.countDown()
            check(resume.await(10, TimeUnit.SECONDS))
        }
        @Implementation fun release() { releases.incrementAndGet() }
    }
    @Test fun cancellationWaitingForPublicationLockDisposesInsteadOfPublishing() = runBlocking {
        entered = CountDownLatch(1); resume = CountDownLatch(1); constructingThread = null; releases.set(0)
        val dir = temporary.newFolder()
        File(dir, "tokens.txt").writeText("x".repeat(1001))
        val model = File(dir, "model.onnx").also { RandomAccessFile(it, "rw").use { f -> f.setLength(1_000_001) } }
        val engine = SherpaInferenceEngine(RuntimeEnvironment.getApplication())
        val lock = engine.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(engine)
        val work = launch(Dispatchers.IO) { engine.load(model) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            synchronized(lock) {
                resume.countDown()
                // Android's compile API omits java.management; this is a JVM-only test witness.
                val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
                val getInfo = Class.forName("java.lang.management.ThreadMXBean").getMethod("getThreadInfo", java.lang.Long.TYPE)
                val infoType = Class.forName("java.lang.management.ThreadInfo")
                val lockType = Class.forName("java.lang.management.LockInfo")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (true) {
                    val info = getInfo.invoke(bean, constructingThread!!.id)
                    val lockInfo = info?.let { infoType.getMethod("getLockInfo").invoke(it) }
                    if (info != null && infoType.getMethod("getThreadState").invoke(info) == Thread.State.BLOCKED &&
                        lockInfo != null && lockType.getMethod("getIdentityHashCode").invoke(lockInfo) == System.identityHashCode(lock)) break
                    assertTrue("Load never reached the engine publication lock", System.nanoTime() < deadline)
                    Thread.sleep(1)
                }
                work.cancel()
            }
            work.join()
            assertTrue(work.isCancelled)
            assertEquals("Cancelled unpublished recognizer must be disposed", 1, releases.get())
        } finally { resume.countDown(); work.cancelAndJoin(); engine.release() }
        assertEquals(1, releases.get())
    }
}
