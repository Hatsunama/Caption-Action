package com.hatsunama.captionaction.inference

import java.io.File

/**
 * Placeholder for a future whisper.cpp / Sherpa-ONNX JNI bridge.
 * Returns null until native libraries are packaged under jniLibs.
 *
 * Suggested integration:
 * - whisper.cpp Android example (ggml models) — load [modelFile] with whisper_init_from_file
 * - or com.k2fsa.sherpa:sherpa-onnx Android AAR with Whisper / SenseVoice
 */
object WhisperCppBridge {
    val isNativeAvailable: Boolean
        get() = false // flip when libwhisper.so / sherpa-onnx is packaged

    fun createEngine(): InferenceEngine? {
        if (!isNativeAvailable) return null
        return null
    }
}
