package com.hatsunama.captionaction.inference

import com.hatsunama.captionaction.data.AppSettings

/**
 * Translation policy + optional local MT.
 *
 * MVP: Demo engine may supply [CaptionResult.translatedText]. Dedicated offline MT
 * is structured but not bundled — passthrough + dual UI are fully wired.
 */
interface TranslationEngine {
    fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult
}

class PassthroughTranslationEngine : TranslationEngine {
    override fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult {
        val detected = result.language.lowercase()
        val target = settings.targetLanguage.lowercase()
        val passthrough = settings.passthroughLanguages.map { it.lowercase() }.toSet()
        val hasDemoTranslation = !result.translatedText.isNullOrBlank() &&
            result.translatedText != result.text

        return when {
            settings.dualSubtitles && hasDemoTranslation -> {
                // Show translated as primary, original as secondary when dual is on.
                result.copy(
                    text = result.text,
                    translatedText = result.translatedText
                )
            }
            detected in passthrough || detected == target -> {
                result.copy(translatedText = if (settings.dualSubtitles) result.text else null)
            }
            hasDemoTranslation -> {
                result.copy(translatedText = result.translatedText)
            }
            else -> {
                result.copy(
                    translatedText = if (settings.dualSubtitles) result.text else null
                )
            }
        }
    }
}
