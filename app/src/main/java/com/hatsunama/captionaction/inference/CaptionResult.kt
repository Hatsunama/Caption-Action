package com.hatsunama.captionaction.inference

data class CaptionResult(
    val text: String,
    val language: String,
    val confidence: Float,
    val startMs: Long,
    val endMs: Long,
    val translatedText: String? = null
)
