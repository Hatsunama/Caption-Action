package com.hatsunama.captionaction.inference

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowWhisperJNI::class], instrumentedPackages = ["dev.ffmpegkit.whisper"])
class WhisperCppLifetimeTest {
    private lateinit var engine: WhisperCppInferenceEngine
    private lateinit var modelFile: File
    @Before fun setup() {
        ShadowWhisperJNI.reset()
        val context = RuntimeEnvironment.getApplication()
        engine = WhisperCppInferenceEngine(context)
        modelFile = File(context.cacheDir, "ggml-fixture.bin")
        RandomAccessFile(modelFile, "rw").use { it.setLength(1_000_001) }
    }
    private suspend fun infer() = engine.transcribeWindow(ShortArray(16_000) { 1000 }, 16_000, true)
    private fun assertNoWav() {
        assertTrue(RuntimeEnvironment.getApplication().cacheDir.listFiles()!!.none { it.name.startsWith("whisper_chunk_") })
    }
    @Test fun releaseRetiresWithoutFreeingActiveNativeCall() = runBlocking {
        assertTrue(engine.load(modelFile))
        val work = async(Dispatchers.Default) { infer() }
        try {
            ShadowWhisperJNI.await(ShadowWhisperJNI.entered)
            engine.release() // Must return before the blocking JNI call completes.
            assertEquals("native free must wait for in-flight use", 0, ShadowWhisperJNI.frees.get())
            assertNull(infer())
        } finally {
            ShadowWhisperJNI.proceed.countDown()
            work.await()
            engine.release()
        }
        ShadowWhisperJNI.await(ShadowWhisperJNI.freed)
        assertEquals(1, ShadowWhisperJNI.frees.get())
        assertNoWav()
    }
    @Test fun cancelledLoadCannotPublishAfterRelease() = runBlocking {
        ShadowWhisperJNI.loadProceed = CountDownLatch(1)
        val loading = launch(Dispatchers.Default) { engine.load(modelFile) }
        try {
            ShadowWhisperJNI.await(ShadowWhisperJNI.loadEntered)
            loading.cancel()
            engine.release()
        } finally {
            ShadowWhisperJNI.loadProceed.countDown()
            loading.join()
        }
        // Check before another release can mask a leaked or republished allocation.
        assertTrue("cancelled allocation must eventually be reclaimed", ShadowWhisperJNI.freed.await(10, TimeUnit.SECONDS))
        assertEquals("cancelled allocation must be reclaimed", 1, ShadowWhisperJNI.frees.get())
        ShadowWhisperJNI.proceed.countDown()
        assertNull(infer())
        assertEquals(0, ShadowWhisperJNI.calls.get())
        engine.release()
        assertEquals(1, ShadowWhisperJNI.frees.get())
    }
    @Test fun cancellationAndNativeFailureStillFreeExactlyOnceAndDeleteWav() = runBlocking {
        assertTrue(engine.load(modelFile))
        ShadowWhisperJNI.failTranscription = true
        val work = launch(Dispatchers.Default) { infer() }
        try {
            ShadowWhisperJNI.await(ShadowWhisperJNI.entered)
            work.cancel()
            engine.release()
            assertEquals("cancel does not stop JNI", 0, ShadowWhisperJNI.frees.get())
        } finally {
            ShadowWhisperJNI.proceed.countDown()
            work.join()
            engine.release()
        }
        ShadowWhisperJNI.await(ShadowWhisperJNI.freed)
        assertEquals(1, ShadowWhisperJNI.frees.get())
        assertNoWav()
    }
    @Test fun loadPreservesExplicitSourceLanguage() = runBlocking {
        engine.setSourceLanguage("es")
        assertTrue(engine.load(modelFile))
        ShadowWhisperJNI.proceed.countDown()
        try {
            assertNotNull(infer())
            assertEquals("es", ShadowWhisperJNI.language)
            assertEquals("mic-source-es", engine.lastAsrMode())
        } finally {
            engine.release()
            ShadowWhisperJNI.await(ShadowWhisperJNI.freed)
        }
    }
    @Test fun releaseInvalidatesAnUncancelledBlockedLoad() = runBlocking {
        ShadowWhisperJNI.loadProceed = CountDownLatch(1)
        val loading = async(Dispatchers.Default) { engine.load(modelFile) }
        ShadowWhisperJNI.await(ShadowWhisperJNI.loadEntered)
        engine.release()
        ShadowWhisperJNI.loadProceed.countDown()
        try {
            assertFalse("retired load must not publish", loading.await())
            ShadowWhisperJNI.await(ShadowWhisperJNI.freed)
            assertEquals(1, ShadowWhisperJNI.frees.get())
        } finally { engine.release() }
    }

    @Test fun queuedCancelledCallsNeverEnterNativeAndRemainingCallsAreSerial() = runBlocking {
        assertTrue(engine.load(modelFile))
        val threads = Executors.newFixedThreadPool(2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secondReady = CountDownLatch(1)
        ShadowWhisperJNI.secondEntry = secondReady
        // The AAR also uses Default. UND on a test-owned thread runs the real engine/AAR
        // until it either blocks in JNI or suspends at the engine's coroutine mutex.
        val first = threads.submit<Deferred<CaptionResult?>> {
            scope.async(start = CoroutineStart.UNDISPATCHED) { infer() }
        }
        ShadowWhisperJNI.await(ShadowWhisperJNI.entered)
        val second = threads.submit<Deferred<CaptionResult?>> {
            scope.async(start = CoroutineStart.UNDISPATCHED) { infer() }.also { secondReady.countDown() }
        }
        try {
            // Baseline: second JNI entry signals this. Repaired: queued coroutine suspends
            // and async returns. In either case both callers reached the ownership boundary.
            ShadowWhisperJNI.await(secondReady)
            assertEquals("one native context must have only one user", 1, ShadowWhisperJNI.peak.get())
            second.get(10, TimeUnit.SECONDS).cancel()
        } finally {
            ShadowWhisperJNI.proceed.countDown()
            first.get(10, TimeUnit.SECONDS).join()
            second.get(10, TimeUnit.SECONDS).join()
            scope.cancel()
            threads.shutdownNow()
        }
        assertEquals("cancelled queued work must never enter JNI", 1, ShadowWhisperJNI.calls.get())
        assertNotNull(infer())
        assertEquals(2, ShadowWhisperJNI.calls.get())
        assertEquals(1, ShadowWhisperJNI.peak.get())
        engine.release()
        ShadowWhisperJNI.await(ShadowWhisperJNI.freed)
        assertEquals(1, ShadowWhisperJNI.frees.get())
        assertNoWav()
    }
    @Test fun newerLoadWinsAndBothAllocationsAreFreedOnce() = runBlocking {
        val firstGate = CountDownLatch(1)
        ShadowWhisperJNI.loadProceed = firstGate
        val oldLoad = async(Dispatchers.Default) { engine.load(modelFile) }
        ShadowWhisperJNI.await(ShadowWhisperJNI.loadEntered)
        ShadowWhisperJNI.loadProceed = CountDownLatch(0)
        try { assertTrue(engine.load(modelFile)) } finally { firstGate.countDown() }
        assertFalse(oldLoad.await())
        assertEquals(42L, ShadowWhisperJNI.freeEvents.poll(10, TimeUnit.SECONDS))
        ShadowWhisperJNI.proceed.countDown()
        assertNotNull(infer())
        assertEquals(43L, ShadowWhisperJNI.lastHandle)
        engine.release()
        assertEquals(43L, ShadowWhisperJNI.freeEvents.poll(10, TimeUnit.SECONDS))
        engine.release()
        assertEquals(2, ShadowWhisperJNI.frees.get())
    }
    @Test fun explicitReloadAfterReleaseRemainsSupported() = runBlocking {
        assertTrue(engine.load(modelFile))
        engine.release()
        assertEquals(42L, ShadowWhisperJNI.freeEvents.poll(10, TimeUnit.SECONDS))
        assertTrue(engine.load(modelFile))
        ShadowWhisperJNI.proceed.countDown()
        assertNotNull(infer())
        assertEquals(43L, ShadowWhisperJNI.lastHandle)
        engine.release()
        assertEquals(43L, ShadowWhisperJNI.freeEvents.poll(10, TimeUnit.SECONDS))
        assertEquals(2, ShadowWhisperJNI.frees.get())
    }

}
