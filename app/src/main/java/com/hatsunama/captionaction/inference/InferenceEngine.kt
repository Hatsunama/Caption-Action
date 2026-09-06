package com.hatsunama.captionaction.inference

import java.io.File

/**
 * Pluggable on-device ASR. Implementations must run fully locally.
 *
 * MVP ships [DemoInferenceEngine] (timed demo + optional amplitude-driven stubs)
 * so the overlay / capture / settings pipeline is testable without native libs.
 *
 * To plug a real engine (recommended: whisper.cpp Android / Sherpa-ONNX):
 * 1. Add the native AAR or jniLibs for your chosen runtime.
 * 2. Implement this interface loading [modelFile] (ggml / onnx path from ModelCache).
 * 3. Swap the factory in [InferenceEngineFactory].
 * See docs/physical-test.md and README "Plugging a real ASR engine".
 */
interface InferenceEngine {
    val name: String
    suspend fun load(modelFile: File): Boolean
    /** Transcribe one PCM 16-bit mono chunk at [sampleRateHz]. */
    suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult?
    fun release()
}
