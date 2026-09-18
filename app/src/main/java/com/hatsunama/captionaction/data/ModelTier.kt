package com.hatsunama.captionaction.data

/**
 * On-device ASR tiers.
 *
 * - Fast: sherpa-onnx SenseVoice int8 (multilingual zh/en/ja/ko/yue)
 * - Balanced / Accurate: ggml Whisper q5_1 via whisper.cpp (real ASR; English target uses translate)
 */
enum class ModelTier(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxBytes: Long,
    val downloadUrl: String,
    /** Engine family this file is meant for. */
    val engineFamily: EngineFamily
) {
    FAST(
        id = "fast",
        displayName = "Fast",
        fileName = "model.int8.onnx",
        approxBytes = 228L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        engineFamily = EngineFamily.SHERPA_SENSEVOICE
    ),
    BALANCED(
        id = "balanced",
        displayName = "Balanced",
        fileName = "ggml-base-q5_1.bin",
        approxBytes = 57L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
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

    companion object {
        fun fromId(id: String): ModelTier =
            entries.firstOrNull { it.id == id } ?: FAST
    }
}
