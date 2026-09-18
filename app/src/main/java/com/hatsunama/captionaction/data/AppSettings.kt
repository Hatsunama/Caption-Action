package com.hatsunama.captionaction.data

data class AppSettings(
    val permissionsWalkthroughComplete: Boolean = false,
    val modelTierId: String = ModelTier.FAST.id,
    val targetLanguage: String = "en",
    val passthroughLanguages: Set<String> = setOf("en"),
    val dualSubtitles: Boolean = false,
    val saveSubtitlesToFile: Boolean = false,
    val overlayX: Int = 48,
    val overlayY: Int = 200,
    val overlayWidth: Int = 900,
    val overlayHeight: Int = 380,
    val fontIndex: Int = 0
)
