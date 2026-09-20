package com.hatsunama.captionaction.data

data class AppSettings(
    val permissionsWalkthroughComplete: Boolean = false,
    val modelTierId: String = ModelTier.BALANCED.id,
    /** Incoming audio language for Live ASR (Whisper source). */
    val inputLanguage: String = "en",
    val targetLanguage: String = "en",
    /**
     * Deprecated since 0.3.27 — product UI uses [inputLanguage].
     * Kept empty for migration / applyPolicy no-ops; do not write product values.
     */
    val passthroughLanguages: Set<String> = emptySet(),
    val dualSubtitles: Boolean = false,
    val saveSubtitlesToFile: Boolean = false,
    val overlayX: Int = 48,
    val overlayY: Int = 200,
    val overlayWidth: Int = 900,
    val overlayHeight: Int = 380,
    val fontIndex: Int = 0
)
