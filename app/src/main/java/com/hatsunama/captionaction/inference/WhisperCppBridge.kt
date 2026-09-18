package com.hatsunama.captionaction.inference

import android.content.Context
import android.util.Log
import com.hatsunama.captionaction.data.ModelTier

/**
 * Routes to Sherpa SenseVoice (Fast ONNX) or whisper.cpp (Balanced/Accurate ggml).
 * Returns null when the required native stack cannot load — no demo fallback.
 */
object WhisperCppBridge {
    private const val TAG = "WhisperCppBridge"

    @Volatile
    private var sherpaProbe: Boolean? = null

    @Volatile
    private var whisperProbe: Boolean? = null

    val isSherpaAvailable: Boolean
        get() = sherpaProbe ?: probeSherpa().also { sherpaProbe = it }

    val isWhisperAvailable: Boolean
        get() = whisperProbe ?: probeWhisper().also { whisperProbe = it }

    fun createEngine(
        context: Context,
        tier: ModelTier,
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
