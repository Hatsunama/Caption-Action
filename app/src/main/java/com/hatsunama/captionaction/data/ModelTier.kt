package com.hatsunama.captionaction.data

/**
 * Three on-device multilingual ASR tiers.
 * Download URLs are ggml Whisper q5_1 multilanguage weights from ggml-org/whisper.cpp
 * (Hugging Face), sized to match Fast≈31MB / Balanced≈57MB / Accurate≈182MB.
 */
enum class ModelTier(
    val id: String,
    val displayName: String,
    val fileName: String,
    val approxBytes: Long,
    val downloadUrl: String
) {
    FAST(
        id = "fast",
        displayName = "Fast",
        fileName = "ggml-tiny-q5_1.bin",
        approxBytes = 31L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
    ),
    BALANCED(
        id = "balanced",
        displayName = "Balanced",
        fileName = "ggml-base-q5_1.bin",
        approxBytes = 57L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
    ),
    ACCURATE(
        id = "accurate",
        displayName = "Accurate",
        fileName = "ggml-small-q5_1.bin",
        approxBytes = 182L * 1024 * 1024,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
    );

    companion object {
        fun fromId(id: String): ModelTier =
            entries.firstOrNull { it.id == id } ?: BALANCED
    }
}
