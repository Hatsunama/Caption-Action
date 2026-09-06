package com.hatsunama.captionaction.inference

object InferenceEngineFactory {
    fun create(): InferenceEngine {
        return WhisperCppBridge.createEngine() ?: DemoInferenceEngine()
    }
}
