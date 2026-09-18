package com.hatsunama.captionaction.inference

import java.io.File

interface InferenceEngine {
    val name: String

    fun offlineTranslationTargets(): Set<String> = emptySet()

    fun canProvideDualSubtitles(): Boolean = false

    fun setTargetLanguage(code: String) {}

    fun setDualSubtitles(enabled: Boolean) {}

    suspend fun load(modelFile: File): Boolean

    suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult?

    fun release()
}
