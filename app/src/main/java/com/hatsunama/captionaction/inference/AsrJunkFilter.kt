package com.hatsunama.captionaction.inference

import android.util.Log

/**
 * Rejects ASR hallucination / filler tokens that are not real speech captions.
 * SenseVoice and whisper both invent short loops ("yeah. yeah.", "嗯") and
 * YouTube-outro clichés on quiet / non-speech audio. Callers treat null as "no caption".
 */
object AsrJunkFilter {
    private const val TAG = "AsrJunkFilter"

    /** Exact normalized phrases (lowercase, punctuation stripped to spaces). */
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
        // English fillers / loops
        "hmm",
        "mm",
        "mhm",
        "uh",
        "um",
        "uh huh",
        "uh-huh",
        "ah",
        "oh",
        "eh",
        "er",
        "huh",
        "ahem",
        "yeah",
        "yea",
        "yep",
        "yup",
        // YouTube / VOD outro hallucinations (whisper Quality path)
        "thank you for watching",
        "thanks for watching",
        "thanks for watching please subscribe",
        "thank you for watching please subscribe",
        "please subscribe",
        "please like and subscribe",
        "like and subscribe",
        "subscribe",
        "thanks for listening",
        "thank you for listening",
        // Chinese fillers / outros (SenseVoice live)
        "嗯",
        "啊",
        "哦",
        "呃",
        "嘿",
        "哈",
        "哎",
        "咦",
        "唔",
        "嗯嗯",
        "啊啊",
        "哦哦",
        "谢谢观看",
        "感谢观看",
        "谢谢收看",
        "感谢收看",
        "请订阅",
        "订阅",
        "点赞订阅"
    )

    /** Short tokens that, alone or only-repeated, are never useful captions. */
    private val FILLER_TOKENS = setOf(
        "hmm", "mm", "mhm", "uh", "um", "ah", "oh", "eh", "er", "huh", "ahem",
        "yeah", "yea", "yep", "yup",
        "嗯", "啊", "哦", "呃", "嘿", "哈", "哎", "咦", "唔"
    )

    private val CJK_FILLER_CHARS = setOf('嗯', '啊', '哦', '呃', '嘿', '哈', '哎', '咦', '唔')

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

        val normalized = normalize(strippedBrackets)
        if (normalized.isEmpty()) return true
        if (normalized in EXACT_JUNK) return true

        // Common whisper silence / blank markers (with or without punctuation)
        val compact = normalized.replace(Regex("""\s+"""), "")
        if (compact == "blankaudio" ||
            compact == "nospeech" ||
            compact == "silence" ||
            compact == "blank" ||
            compact == "music" ||
            compact == "thankyouforwatching" ||
            compact == "thanksforwatching" ||
            compact == "pleasesubscribe" ||
            compact == "likeandsubscribe" ||
            compact == "谢谢观看" ||
            compact == "感谢观看" ||
            compact == "谢谢收看" ||
            compact == "请订阅"
        ) {
            return true
        }

        // Entire string is only bracketed tokens / punctuation
        if (t.matches(Regex("""^[\s\[\]{}()<>|.,;:!?'"\-_/\\]+$"""))) return true

        // Filler-only loops: "yeah. yeah.", "Yeah yeah yeah", "嗯 嗯"
        if (isFillerOnlyLoop(normalized)) return true

        // CJK filler char runs with no real content: "嗯嗯嗯", "啊…啊"
        if (isCjkFillerOnly(compact)) return true

        return false
    }

    /** Lowercase + strip punctuation to spaces for stable compare / junk match. */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * True when [a] and [b] are the same caption or a near-duplicate (containment)
     * after normalize — used to suppress re-publishing within a short window.
     */
    fun isNearDuplicate(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        // Short containment: "yeah" vs "yeah yeah" already junk; for real text,
        // suppress if one is a prefix/suffix of the other and both are short.
        if (na.length <= 48 && nb.length <= 48) {
            if (na.startsWith(nb) || nb.startsWith(na)) return true
            if (na.endsWith(nb) || nb.endsWith(na)) return true
        }
        return false
    }

    private fun isFillerOnlyLoop(normalized: String): Boolean {
        val tokens = normalized.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        if (tokens.any { it !in FILLER_TOKENS }) return false
        // Repeated / multi fillers only: "yeah yeah", "oh yeah", "嗯 嗯"
        // Singles are covered by EXACT_JUNK.
        return tokens.size >= 2
    }

    private fun isCjkFillerOnly(compact: String): Boolean {
        if (compact.isEmpty()) return true
        return compact.all { it in CJK_FILLER_CHARS }
    }
}
