package com.hatsunama.captionaction.inference

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real engine and shipped AAR; only JNI returns controlled text.
 * These assertions test configuration, not recognition/translation accuracy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowWhisperJNI::class], instrumentedPackages = ["dev.ffmpegkit.whisper"])
class WhisperLiveLanguageTest {
    private lateinit var engine: WhisperCppInferenceEngine
    private lateinit var modelFile: File

    @Before fun setup() = runBlocking {
        ShadowWhisperJNI.reset()
        ShadowWhisperJNI.proceed.countDown()
        val context = RuntimeEnvironment.getApplication()
        modelFile = File(context.cacheDir, "ggml-language-fixture.bin")
        RandomAccessFile(modelFile, "rw").use { it.setLength(1_000_001) }
        engine = WhisperCppInferenceEngine(context)
        assertTrue(engine.load(modelFile))
        engine.setPlaybackCapture(true)
        engine.setSourceLanguage("") // Actual Live caller contract, independent of Home MT hint.
    }

    @After fun cleanup() { engine.release(); modelFile.delete() }

    private suspend fun checkConfiguration(target: String, dual: Boolean, language: String, translate: Boolean) {
        engine.setTargetLanguage(target)
        engine.setDualSubtitles(dual)
        assertNotNull(engine.transcribeWindow(ShortArray(24_000) { 1000 }, 16_000, true))
        assertEquals("output language must not force the spoken language", language, ShadowWhisperJNI.language)
        assertEquals(translate, ShadowWhisperJNI.translationRequested)
    }

    @Test fun englishSingleAlwaysDetectsSourceEvenAfterLatinSuccess() = runBlocking {
        repeat(4) { checkConfiguration("en", false, "auto", true) }
        assertEquals("auto-translate-en", engine.lastAsrMode())
    }

    @Test fun togglingDualAndTargetNeverChangesAutoSourceContract() = runBlocking {
        checkConfiguration("en", true, "auto", false)
        checkConfiguration("ja", false, "auto", false)
        checkConfiguration("en", false, "auto", true)
        checkConfiguration("en", true, "auto", false)
    }

    @Test fun explicitMicSourceStillTranscribesWithoutWhisperTranslation() = runBlocking {
        engine.setPlaybackCapture(false)
        engine.setSourceLanguage("zh")
        checkConfiguration("en", false, "zh", false)
        assertEquals("mic-source-zh", engine.lastAsrMode())
    }
}
