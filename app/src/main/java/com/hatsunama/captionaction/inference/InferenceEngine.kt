package com.hatsunama.captionaction.inference

import java.io.File

interface InferenceEngine {
    val name: String

    /** Offline MT targets this engine can emit beyond raw ASR (e.g. whisper translate → en). */
    fun offlineTranslationTargets(): Set<String> = emptySet()

    fun setTargetLanguage(code: String) {}

    suspend fun load(modelFile: File): Boolean

    suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult?

    fun release()
}
