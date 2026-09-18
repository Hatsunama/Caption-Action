package com.hatsunama.captionaction.inference

import java.io.File

/**
 * Pluggable on-device ASR. Implementations must run fully locally.
 *
 * Runtime selection ([InferenceEngineFactory]):
 * - [SherpaInferenceEngine] for Fast SenseVoice ONNX
 * - [WhisperCppInferenceEngine] for Balanced/Accurate ggml whisper.cpp
 * No demo/stub fallback — if create/load fails the session must show an error and stop.
 */
interface InferenceEngine {
    val name: String
    suspend fun load(modelFile: File): Boolean
    /** Transcribe one PCM 16-bit mono chunk at [sampleRateHz]. */
    suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult?
    fun release()
}
