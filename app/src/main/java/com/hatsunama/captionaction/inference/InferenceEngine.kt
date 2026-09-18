package com.hatsunama.captionaction.inference

import java.io.File

interface InferenceEngine {
    val name: String

    fun offlineTranslationTargets(): Set<String> = emptySet()

    /** Dual is enabled when TranslationEngine can supply translatedText (ML Kit / whisper EN). */
    fun canProvideDualSubtitles(): Boolean = false

    fun setTargetLanguage(code: String) {}

    fun setDualSubtitles(enabled: Boolean) {}

    /**
     * When true, PCM comes from AudioPlaybackCapture (internal mix).
     * Engines must not drop quiet/muted-volume frames with mic-style RMS gates —
     * digital playback can be valid at very low amplitude when speaker volume is 0.
     */
    fun setPlaybackCapture(enabled: Boolean) {}

    suspend fun load(modelFile: File): Boolean

    suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult?

    fun release()
}
