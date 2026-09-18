package com.hatsunama.captionaction.inference

import android.util.Log

/**
 * Rejects whisper (and similar) hallucination tokens that are not real speech,
 * e.g. [BLANK_AUDIO], [Silence], [Music]. Callers must treat null as "no caption".
 */
object AsrJunkFilter {
    private const val TAG = "AsrJunkFilter"

    private val EXACT_JUNK = setOf(
        "blank_audio",
        "blank",
        "silence",
        "music",
        "inaudible",
        "inaudible speech",
        "no speech",
        "nospeech",
        "laughing",
        "laughter",
        "applause",
        "crying",
        "sigh",
        "cough",
        "clicking",
        "click",
        "hmm",
        "mm",
        "mhm",
        "uh",
        "um"
    )

    /**
     * @return trimmed text if usable as a caption, else null (and logs at INFO).
     */
    fun sanitizeOrNull(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (isJunk(trimmed)) {
            Log.i(TAG, "filtered junk ASR text: ${trimmed.take(80)}")
            Log.i("CaptionAction", "filtered=true junk=${trimmed.take(80)}")
            return null
        }
        return trimmed
    }

    fun isJunk(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true

        // Bracket/paren-only token(s): [BLANK_AUDIO], (Silence), {BLANK_AUDIO], etc.
        val strippedBrackets = t
            .replace(Regex("""[\[\]{}()<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (strippedBrackets.isEmpty()) return true

        val lower = strippedBrackets.lowercase()
        if (lower in EXACT_JUNK) return true

        // Common whisper silence / blank markers (with or without punctuation)
        val compact = lower.replace(Regex("""[\s._\-]+"""), "")
        if (compact == "blankaudio" ||
            compact == "nospeech" ||
            compact == "silence" ||
            compact == "blank" ||
            compact == "music"
        ) {
            return true
        }

        // Entire string is only bracketed tokens / punctuation
        if (t.matches(Regex("""^[\s\[\]{}()<>|.,;:!?'"\-_/\\]+$"""))) return true

        return false
    }
}
