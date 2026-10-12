package com.hatsunama.captionaction.inference

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [SherpaLoggingPrivacyTest.RecognizerBoundary::class, SherpaLoggingPrivacyTest.StreamBoundary::class],
    instrumentedPackages = ["com.k2fsa.sherpa.onnx"])
class SherpaLoggingPrivacyTest {
    companion object { var failure: Throwable? = null; var streamReleases = 0 }
    @org.junit.Before fun reset() { ShadowLog.clear(); failure = null; streamReleases = 0 }

    @Implements(value = OfflineRecognizer::class, isInAndroidSdk = false)
    class RecognizerBoundary {
        companion object { @JvmStatic @Implementation fun __staticInitializer__() {} }
        @Implementation fun createStream(): OfflineStream = Shadow.newInstanceOf(OfflineStream::class.java)
        @Implementation fun decode(stream: OfflineStream) { failure?.let { throw it } }
        @Implementation fun getResult(stream: OfflineStream) =
            OfflineRecognizerResult("紫色灯笼守护我们的秘密", emptyArray(), floatArrayOf(), "zh", "", "", floatArrayOf())
    }
    @Implements(value = OfflineStream::class, isInAndroidSdk = false)
    class StreamBoundary {
        companion object { @JvmStatic @Implementation fun __staticInitializer__() {} }
        @Implementation fun acceptWaveform(samples: FloatArray, sampleRate: Int) {}
        @Implementation fun release() { streamReleases++ }
    }
    @Test fun sherpaDecodeFailurePreservesCleanupWithoutThrowableChainContent() = runBlocking {
        val marker = "CinnabarSherpaPrivacyMarker"
        failure = IllegalStateException("decode failed", IllegalArgumentException(marker))
        val engine = SherpaInferenceEngine(RuntimeEnvironment.getApplication())
        engine.javaClass.getDeclaredField("recognizer").apply { isAccessible = true }
            .set(engine, Shadow.newInstanceOf(OfflineRecognizer::class.java))
        assertNull(engine.transcribeWindow(ShortArray(32_000) { 1000 }, 16_000, true))
        assertEquals(1, streamReleases)
        val logs = ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertTrue(logs.contains("transcribe failed"))
        assertFalse("Sherpa exception leaked speech: $logs", logs.contains(marker))
    }

    @Test fun retainedSherpaRejectionLogsReasonWithoutSpeech() = runBlocking {
        ShadowLog.clear()
        val engine = SherpaInferenceEngine(RuntimeEnvironment.getApplication())
        engine.javaClass.getDeclaredField("recognizer").apply { isAccessible = true }
            .set(engine, Shadow.newInstanceOf(OfflineRecognizer::class.java))
        engine.javaClass.getDeclaredField("scriptBias").apply { isAccessible = true }.setInt(engine, -2)
        assertNull(engine.transcribeWindow(ShortArray(32_000) { 1000 }, 16_000, true))
        assertEquals("auto-reject-cjk-dump", engine.lastAsrMode())
        val logs = ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        assertTrue(logs.contains("rejected CJK"))
        assertFalse("Sherpa rejected speech leaked into diagnostics: $logs", logs.contains("紫色灯笼守护我们的秘密"))
    }
}
