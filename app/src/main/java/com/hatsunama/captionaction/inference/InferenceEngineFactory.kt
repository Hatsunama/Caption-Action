package com.hatsunama.captionaction.inference

import android.content.Context
import com.hatsunama.captionaction.data.ModelTier

/**
 * Creates the on-device ASR engine for the selected [ModelTier].
 * Fast → Sherpa SenseVoice; Balanced/Accurate → whisper.cpp ggml.
 * Returns null when native cannot load — callers must show an error (no stub captions).
 */
object InferenceEngineFactory {
    fun create(
        context: Context,
        tier: ModelTier = ModelTier.FAST,
        targetLanguage: String = "en"
    ): InferenceEngine? {
        return WhisperCppBridge.createEngine(context, tier, targetLanguage)
    }
}
