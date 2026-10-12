package com.hatsunama.captionaction.inference

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translator
import com.hatsunama.captionaction.data.AppSettings
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Real engine/policy/filter bodies and Android log sink; only native inference and SDK
 * translation are synthetic. No model download or native inference is performed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowWhisperJNI::class, TranscriptLoggingPrivacyTest.ModelManagerBoundary::class, MlKitTaskBoundary.TranslationFactory::class, MlKitTaskBoundary.IdentifierFactory::class], instrumentedPackages = ["dev.ffmpegkit.whisper", "com.google.mlkit.common.model.RemoteModelManager", "com.google.mlkit.nl.translate.Translation", "com.google.mlkit.nl.languageid.LanguageIdentification"])
class TranscriptLoggingPrivacyTest {
    @org.robolectric.annotation.Implements(value = com.google.mlkit.common.model.RemoteModelManager::class, isInAndroidSdk = false)
    class ModelManagerBoundary {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation
            fun getInstance(): com.google.mlkit.common.model.RemoteModelManager =
                org.robolectric.shadow.api.Shadow.newInstanceOf(com.google.mlkit.common.model.RemoteModelManager::class.java)
        }
    }

    @Before fun reset() { ShadowLog.clear(); ShadowWhisperJNI.reset(); MlKitTaskBoundary.reset() }

    private fun assertPrivate(vararg markers: String) {
        val logs = ShadowLog.getLogs().joinToString("\n") { it.msg + (it.throwable?.stackTraceToString() ?: "") }
        for (marker in markers) assertFalse("Speech leaked to diagnostics: $logs", logs.contains(marker))
    }

    @Test fun nativeAcceptedSpeechRemainsCaptionButNotDiagnosticPreview() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val model = File(context.cacheDir, "ggml-privacy.bin")
        RandomAccessFile(model, "rw").use { it.setLength(1_000_001) }
        val engine = WhisperCppInferenceEngine(context)
        ShadowWhisperJNI.proceed.countDown()
        try {
            assertTrue(engine.load(model))
            val result = engine.transcribeWindow(ShortArray(16_000) { 1000 }, 16_000, true)
            assertEquals("The blue train arrives at seven", result?.text)
            assertTrue(ShadowLog.getLogs().any { it.msg.contains("whisper native") })
            assertPrivate("The blue train arrives at seven")
        } finally { engine.release() }
    }

    @Test fun whisperTranscriptionFailurePreservesNullFallbackWithoutThrowableContent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val model = File(context.cacheDir, "ggml-failure-privacy.bin")
        RandomAccessFile(model, "rw").use { it.setLength(1_000_001) }
        val engine = WhisperCppInferenceEngine(context)
        ShadowWhisperJNI.proceed.countDown()
        ShadowWhisperJNI.failTranscription = true
        try {
            assertTrue(engine.load(model))
            assertNull(engine.transcribeWindow(ShortArray(16_000) { 1000 }, 16_000, true))
            assertTrue(ShadowLog.getLogs().any { it.msg.contains("whisper transcribe failed") })
            assertTrue(context.cacheDir.listFiles()!!.none { it.name.startsWith("whisper_chunk_") })
            assertPrivate("controlled JNI failure")
        } finally { engine.release() }
    }

    @Test fun languageIdentificationFailurePreservesSourceWithoutEchoingSpeech() = runBlocking {
        val marker = "CeruleanLanguageIdPrivacyMarker"
        val source = "The secret lantern travels northward"
        val engine = MlKitTranslationEngine(RuntimeEnvironment.getApplication())
        val type = com.google.mlkit.nl.languageid.LanguageIdentifier::class.java
        val languageId = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            when (method.name) {
                "identifyLanguage" -> Tasks.forException<String>(IllegalStateException(marker))
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as com.google.mlkit.nl.languageid.LanguageIdentifier
        MlKitTaskBoundary.identifierOverride = languageId
        val result = engine.applyPolicy(CaptionResult(source, "auto", 1f, 0, 1000),
            AppSettings(inputLanguage = "", targetLanguage = "en"))
        assertEquals(source, result.text)
        assertNull(result.translatedText)
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("language-id failed") })
        assertPrivate(marker, source)
    }

    @Test fun rejectedJunkDoesNotLeakAtEitherFilterTag() {
        val marker = "foreign language VermilionPrivacyMarker"
        assertNull(AsrJunkFilter.sanitizeOrNull(marker))
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("filtered=true") })
        assertPrivate(marker, "VermilionPrivacyMarker")
    }

    private fun translation(output: String, failure: Boolean = false): MlKitTranslationEngine {
        val engine = MlKitTranslationEngine(RuntimeEnvironment.getApplication())
        val translator = Proxy.newProxyInstance(Translator::class.java.classLoader, arrayOf(Translator::class.java)) { _, method, _ ->
            when (method.name) {
                "translate" -> if (failure) Tasks.forException<String>(IllegalStateException(output)) else Tasks.forResult(output)
                "close" -> null
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as Translator
        MlKitTaskBoundary.translatorOverride = translator
        return engine
    }

    @Test fun actualLiveTranslationPolicyPreservesOutputWithoutLoggingEitherText() = runBlocking {
        val source = "La linterna carmesi protege nuestro secreto"
        val output = "The crimson lantern protects our secret"
        val result = translation(output).applyPolicy(CaptionResult(source, "es", 1f, 0, 1000), AppSettings(inputLanguage = "es", targetLanguage = "en"))
        assertEquals(source, result.text)
        assertEquals(output, result.translatedText)
        assertPrivate(source.take(40), output.take(40))
    }

    @Test fun actualExplicitTranslationPreservesOutputWithoutLoggingEitherText() = runBlocking {
        val source = "Nuestra clave privada es amatista"
        val output = "Our private phrase is amethyst"
        assertEquals(output, translation(output).translateExplicit(source, "es", "en"))
        assertPrivate(source, output)
    }

    @Test fun liveCrumbRejectionDoesNotLogTranslationFragment() = runBlocking {
        val output = "Azul"
        val result = translation(output).applyPolicy(
            CaptionResult("La linterna carmesi protege nuestro secreto", "es", 1f, 0, 1000),
            AppSettings(inputLanguage = "es", targetLanguage = "en"))
        assertNull(result.translatedText)
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("crumb") })
        assertPrivate(output)
    }

    @Test fun sdkFailureCannotEchoSpeechThroughExceptionMessage() = runBlocking {
        val marker = "CobaltFailurePrivacyMarker"
        assertNull(translation(marker, failure = true).translateExplicit("Nuestra clave es privada", "es", "en"))
        assertTrue(ShadowLog.getLogs().any { it.msg.contains("failed/skipped") })
        assertPrivate(marker)
    }
}
