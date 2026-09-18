package com.hatsunama.captionaction.inference

import android.content.Context
import android.util.Log
import com.hatsunama.captionaction.data.ModelTier
import com.hatsunama.captionaction.util.Languages

object InferenceEngineFactory {
    private const val TAG = "InferenceEngineFactory"

    @Volatile private var sherpaProbe: Boolean? = null
    @Volatile private var whisperProbe: Boolean? = null

    val isSherpaAvailable: Boolean
        get() = sherpaProbe ?: probeSherpa().also { sherpaProbe = it }

    val isWhisperAvailable: Boolean
        get() = whisperProbe ?: probeWhisper().also { whisperProbe = it }

    /** Offline MT targets via ML Kit Translate (Languages.kt set). Independent of ASR tier. */
    @Suppress("UNUSED_PARAMETER")
    fun offlineTranslationTargets(tier: ModelTier): Set<String> =
        Languages.all.map { it.code }.filter { MlKitTranslationEngine.isSupportedTarget(it) }.toSet()

    /** Dual works whenever ML Kit (or whisper EN) can supply original + translated. */
    @Suppress("UNUSED_PARAMETER")
    fun canProvideDualSubtitles(tier: ModelTier, targetLanguage: String): Boolean {
        val target = targetLanguage.trim().lowercase()
        return target.isNotEmpty() && MlKitTranslationEngine.isSupportedTarget(target)
    }

    fun create(
        context: Context,
        tier: ModelTier = ModelTier.FAST,
        targetLanguage: String = "en"
    ): InferenceEngine? {
        return when (tier.engineFamily) {
            ModelTier.EngineFamily.SHERPA_SENSEVOICE -> {
                if (!isSherpaAvailable) return null
                try {
                    SherpaInferenceEngine(context.applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "SherpaInferenceEngine construct failed", t)
                    null
                }
            }
            ModelTier.EngineFamily.WHISPER_CPP_GGML -> {
                if (!isWhisperAvailable) return null
                try {
                    WhisperCppInferenceEngine(
                        appContext = context.applicationContext,
                        targetLanguage = targetLanguage
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "WhisperCppInferenceEngine construct failed", t)
                    null
                }
            }
        }
    }

    private fun probeSherpa(): Boolean {
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sherpa-onnx-jni not loadable: ${t.message}")
            false
        }
    }

    private fun probeWhisper(): Boolean = WhisperCppInferenceEngine.isNativeAvailable()
}
