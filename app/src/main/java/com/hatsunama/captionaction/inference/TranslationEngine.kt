package com.hatsunama.captionaction.inference

import com.hatsunama.captionaction.data.AppSettings

/**
 * Translation policy + optional local MT.
 *
 * Contract after [applyPolicy]:
 * - [CaptionResult.text] = original ASR / source language text
 * - [CaptionResult.translatedText] = text in the user's **target** language when
 *   translation is required; null when passthrough / already-target / no MT
 *
 * Overlay should show [translatedText] ?: [text] as the primary (target) line.
 */
interface TranslationEngine {
    fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult
}

class PassthroughTranslationEngine : TranslationEngine {
    override fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult {
        val detected = normalizeLang(result.language)
        val target = normalizeLang(settings.targetLanguage)
        val passthrough = settings.passthroughLanguages.map { normalizeLang(it) }.toSet()

        // Passthrough languages: keep original; no forced translation.
        if (detected.isNotEmpty() && detected in passthrough) {
            return result.copy(translatedText = null)
        }

        // Already in the selected output language.
        if (detected.isNotEmpty() && detected == target) {
            return result.copy(translatedText = null)
        }

        // Need output in target language.
        val towardTarget = result.translatedText
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(result.text, ignoreCase = false) }

        return if (towardTarget != null) {
            // Engine already supplied a target-language line (if any).
            result.copy(translatedText = towardTarget)
        } else {
            // No offline MT for this pair yet — keep original honestly.
            result.copy(translatedText = null)
        }
    }

    private fun normalizeLang(code: String): String {
        val c = code.trim().lowercase()
        if (c.isEmpty() || c == "auto" || c == "unknown") return ""
        // SenseVoice / sherpa may return "en-US", "<|en|>", etc.
        val cleaned = c
            .removePrefix("<|")
            .removeSuffix("|>")
            .substringBefore('-')
            .substringBefore('_')
        return cleaned
    }
}

/**
 * Resolves what the overlay should show given policy output + dual mode.
 * Primary is always the user-facing (target / passthrough) line.
 */
object CaptionDisplay {
    fun primaryAndSecondary(policy: CaptionResult, dualSubtitles: Boolean): Pair<String, String?> {
        val original = policy.text
        val inTarget = policy.translatedText
        val primary = inTarget?.takeIf { it.isNotBlank() } ?: original
        return if (dualSubtitles && inTarget != null && inTarget != original) {
            primary to original
        } else {
            primary to null
        }
    }
}
