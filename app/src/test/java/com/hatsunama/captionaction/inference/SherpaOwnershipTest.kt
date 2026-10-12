package com.hatsunama.captionaction.inference

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/** Actual engine methods; only the AAR's native constructor/recognizer/stream boundary is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SherpaOwnershipTest.RecognizerBoundary::class, SherpaOwnershipTest.StreamBoundary::class],
    instrumentedPackages = ["com.k2fsa.sherpa.onnx"])
class SherpaOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    class Gate {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        fun block() { entered.countDown(); check(resume.await(15, TimeUnit.SECONDS)) { "Gate timed out" } }
        fun await() { assertTrue("Boundary was not reached", entered.await(10, TimeUnit.SECONDS)) }
    }
    class Plan {
        var construct: Gate? = null
        var decodeGate: Gate? = null
        var failureStage: String? = null
        var constructionFailure = false
        val releases = AtomicInteger()
        val streamReleases = AtomicInteger()
        val decodes = AtomicInteger()
        val nativeCallsAfterRelease = AtomicInteger()
        fun call(stage: String) {
            if (releases.get() != 0) nativeCallsAfterRelease.incrementAndGet()
            if (stage == failureStage) throw IllegalStateException("boundary failure")
        }
    }
    companion object { val plans = ConcurrentHashMap<String, Plan>() }
    @org.junit.Before fun reset() { plans.clear() }
    @Implements(value = OfflineRecognizer::class, isInAndroidSdk = false)
    class RecognizerBoundary {
        lateinit var plan: Plan
        companion object { @JvmStatic @Implementation fun __staticInitializer__() {} }
        @Implementation fun __constructor__(assets: AssetManager?, config: OfflineRecognizerConfig) {
            plan = plans.getValue(config.modelConfig.senseVoice.model)
            plan.construct?.block()
            if (plan.constructionFailure) throw IllegalStateException("construction failure")
        }
        @Implementation fun createStream(): OfflineStream {
            plan.call("create")
            return Shadow.newInstanceOf(OfflineStream::class.java).also {
                Shadow.extract<StreamBoundary>(it).plan = plan
            }
        }
        @Implementation fun decode(stream: OfflineStream) {
            plan.decodes.incrementAndGet()
            plan.decodeGate?.block()
            plan.call("decode")
        }
        @Implementation fun getResult(stream: OfflineStream): OfflineRecognizerResult {
            plan.call("result")
            return OfflineRecognizerResult("The lantern glows beside the window", emptyArray(), floatArrayOf(), "en", "", "", floatArrayOf())
        }
        @Implementation fun release() { plan.releases.incrementAndGet() }
    }
    @Implements(value = OfflineStream::class, isInAndroidSdk = false)
    class StreamBoundary {
        lateinit var plan: Plan
        companion object { @JvmStatic @Implementation fun __staticInitializer__() {} }
        @Implementation fun acceptWaveform(samples: FloatArray, sampleRate: Int) { plan.call("accept") }
        @Implementation fun release() { plan.streamReleases.incrementAndGet(); plan.call("streamRelease") }
    }
    private fun model(plan: Plan): File {
        val dir = temporary.newFolder()
        File(dir, "tokens.txt").writeText("x".repeat(1001))
        return File(dir, "model.onnx").also {
            RandomAccessFile(it, "rw").use { f -> f.setLength(1_000_001) }
            plans[it.absolutePath] = plan
        }
    }
    private fun engine() = SherpaInferenceEngine(RuntimeEnvironment.getApplication())
    private suspend fun decode(engine: SherpaInferenceEngine) = engine.transcribeWindow(ShortArray(32_000) { 1000 }, 16_000, true)

    @Test fun releaseRetiresWithoutFreeingActiveDecodeOrBlockingCaller() = runBlocking {
        val plan = Plan().apply { decodeGate = Gate() }; val engine = engine()
        assertTrue(engine.load(model(plan)))
        val work = async(Dispatchers.Default) { decode(engine) }
        try {
            plan.decodeGate!!.await()
            withTimeout(2000) { withContext(Dispatchers.IO) { engine.release() } }
            assertEquals("Active native use must retain recognizer", 0, plan.releases.get())
        } finally { plan.decodeGate!!.resume.countDown(); work.await(); engine.release() }
        assertEquals(1, plan.streamReleases.get()); assertEquals(1, plan.releases.get())
        assertEquals(0, plan.nativeCallsAfterRelease.get())
        assertNull(decode(engine))
    }

    @Test fun replacementRetiresActiveOldRecognizerUntilDecodeFinishes() = runBlocking {
        val old = Plan().apply { decodeGate = Gate() }; val newer = Plan(); val engine = engine()
        assertTrue(engine.load(model(old)))
        val work = async(Dispatchers.Default) { decode(engine) }
        try {
            old.decodeGate!!.await(); assertTrue(engine.load(model(newer)))
            assertEquals(0, old.releases.get()); assertNotNull(decode(engine))
        } finally { old.decodeGate!!.resume.countDown(); work.await(); engine.release() }
        assertEquals(1, old.releases.get()); assertEquals(1, old.streamReleases.get())
        assertEquals(1, newer.releases.get()); assertEquals(0, old.nativeCallsAfterRelease.get())
    }

    @Test fun releaseDuringConstructionDisposesUnpublishedRecognizer() = runBlocking {
        val plan = Plan().apply { construct = Gate() }; val engine = engine(); val file = model(plan)
        val work = async(Dispatchers.IO) { engine.load(file) }
        try { plan.construct!!.await(); engine.release() } finally { plan.construct!!.resume.countDown() }
        assertFalse("Retired construction cannot publish", work.await())
        assertEquals(1, plan.releases.get()); assertNull(decode(engine)); engine.release()
        assertEquals(1, plan.releases.get())
    }

    @Test fun newerLoadWinsOverLateOldConstruction() = runBlocking {
        val old = Plan().apply { construct = Gate() }; val newer = Plan(); val engine = engine(); val file = model(old)
        val work = async(Dispatchers.IO) { engine.load(file) }
        try { old.construct!!.await(); assertTrue(engine.load(model(newer))) } finally { old.construct!!.resume.countDown() }
        assertFalse(work.await()); assertEquals(1, old.releases.get())
        assertNotNull(decode(engine)); assertEquals(1, newer.decodes.get()); assertEquals(0, newer.releases.get())
        engine.release(); assertEquals(1, newer.releases.get())
    }

    @Test fun lateConstructionFailureCannotRetireNewerLoad() = runBlocking {
        val old = Plan().apply { construct = Gate(); constructionFailure = true }; val newer = Plan(); val engine = engine(); val file = model(old)
        val work = async(Dispatchers.IO) { engine.load(file) }
        try { old.construct!!.await(); assertTrue(engine.load(model(newer))) } finally { old.construct!!.resume.countDown() }
        assertFalse(work.await()); assertNotNull(decode(engine)); assertEquals(0, newer.releases.get())
        engine.release(); assertEquals(1, newer.releases.get())
    }

    @Test fun cancelledConstructionDoesNotPublishAndReleasesExactlyOnce() = runBlocking {
        val plan = Plan().apply { construct = Gate() }; val engine = engine(); val file = model(plan)
        val work = launch(Dispatchers.IO) { engine.load(file) }
        try { plan.construct!!.await(); work.cancel() } finally { plan.construct!!.resume.countDown() }
        work.join(); assertTrue(work.isCancelled)
        assertEquals(1, plan.releases.get()); assertNull(decode(engine)); engine.release(); assertEquals(1, plan.releases.get())
    }

    @Test fun cancellationDuringDecodeKeepsOwnershipThroughStreamCleanup() = runBlocking {
        val plan = Plan().apply { decodeGate = Gate() }; val engine = engine(); assertTrue(engine.load(model(plan)))
        val work = launch(Dispatchers.Default) { decode(engine) }
        try {
            plan.decodeGate!!.await(); work.cancel(); engine.release()
            assertEquals(0, plan.releases.get())
        } finally { plan.decodeGate!!.resume.countDown(); work.join(); engine.release() }
        assertTrue(work.isCancelled); assertEquals(1, plan.streamReleases.get()); assertEquals(1, plan.releases.get())
        assertEquals(0, plan.nativeCallsAfterRelease.get())
    }

    @Test fun decodeFailureAfterRetirementStillCleansStreamBeforeRecognizer() = runBlocking {
        val plan = Plan().apply { decodeGate = Gate(); failureStage = "decode" }; val engine = engine(); assertTrue(engine.load(model(plan)))
        val work = async(Dispatchers.Default) { decode(engine) }
        try { plan.decodeGate!!.await(); engine.release(); assertEquals(0, plan.releases.get()) }
        finally { plan.decodeGate!!.resume.countDown(); work.await(); engine.release() }
        assertEquals(1, plan.streamReleases.get()); assertEquals(1, plan.releases.get()); assertEquals(0, plan.nativeCallsAfterRelease.get())
    }

    @Test fun earlyAudioReturnsDoNotKeepRecognizerAlive() = runBlocking {
        val plan = Plan(); val engine = engine(); assertTrue(engine.load(model(plan)))
        assertNull(engine.transcribeWindow(shortArrayOf(), 16_000, true))
        assertNull(engine.transcribeWindow(ShortArray(100) { 1000 }, 16_000, true))
        assertNull(engine.transcribeWindow(ShortArray(32_000), 16_000, true))
        assertNull(engine.transcribeWindow(ShortArray(100) { 1000 }, 16_000, false))
        engine.release(); assertEquals(1, plan.releases.get()); assertEquals(0, plan.streamReleases.get())
    }

    @Test fun streamReleaseFailureStillDropsRecognizerLeaseExactlyOnce() = runBlocking {
        val plan = Plan().apply { failureStage = "streamRelease" }; val engine = engine(); assertTrue(engine.load(model(plan)))
        assertNotNull(decode(engine)); engine.release(); engine.release()
        assertEquals(1, plan.streamReleases.get()); assertEquals(1, plan.releases.get())
    }

    @Test fun everyNativeFailureReleasesOnlyCreatedStreamsAndDropsLease() = runBlocking {
        for (stage in listOf("create", "accept", "decode", "result")) {
            val plan = Plan().apply { failureStage = stage }; val engine = engine(); assertTrue(engine.load(model(plan)))
            assertNull(decode(engine)); engine.release(); engine.release()
            assertEquals(stage, if (stage == "create") 0 else 1, plan.streamReleases.get())
            assertEquals(stage, 1, plan.releases.get()); assertEquals(stage, 0, plan.nativeCallsAfterRelease.get())
        }
    }
}
