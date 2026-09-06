package com.hatsunama.captionaction.inference

import com.hatsunama.captionaction.data.AppSettings

/**
 * Translation policy + optional local MT.
 *
 * Current MVP: multilingual ASR is expected to emit text already in (or close to)
 * the target language when the model supports it. True offline MT (Marian/OPUS-ONNX)
 * is structured here but not bundled — passthrough + dual UI are fully wired.
 *
 * When [AppSettings.passthroughLanguages] contains the detected language, original
 * text is kept. Dual mode shows original + "translated" line (identity until MT ships).
 */
interface TranslationEngine {
    fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult
}

class PassthroughTranslationEngine : TranslationEngine {
    override fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult {
        val detected = result.language.lowercase()
        val target = settings.targetLanguage.lowercase()
        val passthrough = settings.passthroughLanguages.map { it.lowercase() }.toSet()

        return if (detected in passthrough || detected == target) {
            result.copy(translatedText = if (settings.dualSubtitles) result.text else null)
        } else {
            // Honest limitation: without a local MT model we keep the ASR text and
            // mark translatedText for dual UI as the same string with a note prefix
            // only in dual mode so the UI path is exercised.
            val translated = result.text
            result.copy(
                text = if (settings.dualSubtitles) result.text else translated,
                translatedText = if (settings.dualSubtitles) translated else null
            )
        }
    }
}
