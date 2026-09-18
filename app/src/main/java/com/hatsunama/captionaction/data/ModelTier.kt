package com.hatsunama.captionaction.data

enum class ModelTier(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxBytes: Long,
    val downloadUrl: String,
    val engineFamily: EngineFamily
) {
    /**
     * Optional SenseVoice fast lane (zh/en/ja/ko/yue). Not the default Live product —
     * does not cover all Languages.all.
     */
    FAST(
        id = "fast",
        displayName = "SenseVoice (optional)",
        fileName = "model.int8.onnx",
        approxBytes = 228L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        engineFamily = EngineFamily.SHERPA_SENSEVOICE
    ),
    /**
     * Default Live continuous path + Quality keep-up: whisper.cpp Tiny (language=auto).
     * Covers all Languages.all via multilingual ASR + ML Kit MT.
     */
    BALANCED(
        id = "balanced",
        displayName = "Balanced",
        fileName = "ggml-tiny-q5_1.bin",
        approxBytes = 31L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        engineFamily = EngineFamily.WHISPER_CPP_GGML
    ),
    ACCURATE(
        id = "accurate",
        displayName = "Accurate",
        fileName = "ggml-small-q5_1.bin",
        approxBytes = 182L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        engineFamily = EngineFamily.WHISPER_CPP_GGML
    );

    enum class EngineFamily {
        SHERPA_SENSEVOICE,
        WHISPER_CPP_GGML
    }

    /** Default continuous Live product uses whisper Tiny (Balanced). */
    val isLive: Boolean get() = this == BALANCED
    /** Quality product: Balanced keep-up or Accurate. */
    val isQuality: Boolean get() = engineFamily == EngineFamily.WHISPER_CPP_GGML
    /** Optional SenseVoice lane — not “any language” Live. */
    val isOptionalSenseVoice: Boolean get() = this == FAST

    companion object {
        fun fromId(id: String): ModelTier =
            entries.firstOrNull { it.id == id } ?: BALANCED

        val liveTiers: List<ModelTier> get() = listOf(BALANCED, FAST)
        val qualityTiers: List<ModelTier> get() = entries.filter { it.isQuality }
    }
}
