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

    /** Default path may accumulate across calls until a flush threshold. */
    suspend fun transcribe(pcm16le: ShortArray, sampleRateHz: Int): CaptionResult? =
        transcribeWindow(pcm16le, sampleRateHz, forceFlush = false)

    /**
     * Live consumer path. When [forceFlush] is true, clear the accumulator and run ASR on this
     * PCM immediately (if long enough) — do not re-accumulate across calls returning null in 1–15ms.
     */
    suspend fun transcribeWindow(
        pcm16le: ShortArray,
        sampleRateHz: Int,
        forceFlush: Boolean = false
    ): CaptionResult?

    /** Last ASR mode tag for diagnostics (`en-direct` / `auto-translate`). */
    fun lastAsrMode(): String = ""

    /**
     * When true, the capture queue is behind (overruns / deep backlog).
     * Quality engines shorten their preferred window so each pass finishes sooner.
     */
    fun setKeepUpBehind(behind: Boolean) {}

    /** Preferred PCM window for drain. Live ~2.25s; Quality ~1.5s (shorter when behind). */
    fun preferredWindowSamples(): Int = 36_000

    /** Drop partial ASR accumulator so a newest-window catch-up call is not mixed with stale PCM. */
    fun discardPendingAudio() {}

    fun release()
}
