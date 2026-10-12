package com.hatsunama.captionaction.data

/** Approved identities of the existing upstream artifacts; approxBytes is display-only.
 * Provenance: docs/model-artifact-identities.md. Existing cache bytes must still be verified. */
enum class ModelTier(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxBytes: Long,
    val downloadUrl: String,
    val engineFamily: EngineFamily,
    val expectedBytes: Long,
    val sha256: String
) {
    /**
     * Retained SenseVoice fast lane (zh/en/ja/ko/yue) for engine/on-disk compatibility.
     * Not user-facing in the Live model gate.
     */
    FAST(
        id = "fast",
        displayName = "SenseVoice (optional)",
        fileName = "model.int8.onnx",
        approxBytes = 228L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        engineFamily = EngineFamily.SHERPA_SENSEVOICE,
        expectedBytes = 239_233_841L,
        sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
    ),
    /**
     * Default Live continuous path: whisper.cpp Tiny (language=auto).
     * Covers all Languages.all via multilingual ASR + ML Kit MT.
     */
    BALANCED(
        id = "balanced",
        displayName = "Balanced",
        fileName = "ggml-tiny-q5_1.bin",
        approxBytes = 31L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        engineFamily = EngineFamily.WHISPER_CPP_GGML,
        expectedBytes = 32_152_673L,
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
    ),
    ACCURATE(
        id = "accurate",
        displayName = "Accurate",
        fileName = "ggml-small-q5_1.bin",
        approxBytes = 182L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        engineFamily = EngineFamily.WHISPER_CPP_GGML,
        expectedBytes = 190_085_487L,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    );

    enum class EngineFamily {
        SHERPA_SENSEVOICE,
        WHISPER_CPP_GGML
    }

    /** Default continuous Live product uses whisper Tiny (Balanced). */
    val isLive: Boolean get() = this == BALANCED
    /** Whisper.cpp family (Balanced Live default; Accurate retained for on-disk downloads). */
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
