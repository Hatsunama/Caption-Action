package com.hatsunama.captionaction.inference

import com.hatsunama.captionaction.data.AppSettings

/**
 * Translation policy only — never invents MT.
 * text = ASR source; translatedText = target-language line when the engine supplied one.
 */
interface TranslationEngine {
    fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult
}

class PassthroughTranslationEngine : TranslationEngine {
    override fun applyPolicy(result: CaptionResult, settings: AppSettings): CaptionResult {
        val detected = normalizeLang(result.language)
        val target = normalizeLang(settings.targetLanguage)
        val passthrough = settings.passthroughLanguages.map { normalizeLang(it) }.toSet()

        if (detected.isNotEmpty() && detected in passthrough) {
            return result.copy(translatedText = null)
        }
        if (detected.isNotEmpty() && detected == target) {
            return result.copy(translatedText = null)
        }

        val towardTarget = result.translatedText
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(result.text, ignoreCase = false) }

        return if (towardTarget != null) {
            result.copy(translatedText = towardTarget)
        } else {
            result.copy(translatedText = null)
        }
    }

    private fun normalizeLang(code: String): String {
        val c = code.trim().lowercase()
        if (c.isEmpty() || c == "auto" || c == "unknown") return ""
        return c
            .removePrefix("<|")
            .removeSuffix("|>")
            .substringBefore('-')
            .substringBefore('_')
    }
}

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
