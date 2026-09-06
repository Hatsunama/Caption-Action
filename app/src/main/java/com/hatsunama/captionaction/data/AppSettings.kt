package com.hatsunama.captionaction.data

data class AppSettings(
    val setupComplete: Boolean = true,
    val modelTierId: String = ModelTier.BALANCED.id,
    val targetLanguage: String = "en",
    val passthroughLanguages: Set<String> = setOf("en"),
    val dualSubtitles: Boolean = false,
    val overlayX: Int = 48,
    val overlayY: Int = 200,
    val overlayWidth: Int = 900,
    val overlayHeight: Int = 180,
    val fontIndex: Int = 0,
    val themeIndex: Int = 0,
    val preferPlaybackCapture: Boolean = true
)
