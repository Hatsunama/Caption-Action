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
        "点赞订阅",
        // Ultra-short CJK particles / crumbs from short SenseVoice windows
        "的",
        "了",
        "吧",
        "呢",
        "吗",
        "嘛",
        "呀",
        "哇",
        "着",
        "过",
        "和",
        "与",
        "或",
        "就",
        "都",
        "也",
        "又",
        "还",
        "那",
        "这",
        "是",
        "在",
        "有",
        "不",
        "没",
        "很",
        "太",
        "更"
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
        // Ultra-short crumbs — lone Latin particles / single CJK glyphs (exact junk covers 的/了/…).
        // Keep 2-char CJK words (e.g. 你好) — language-agnostic; do not drop real speech.
        val compactNoSpace = normalized.replace(Regex("""\s+"""), "")
        val cjkInCompact = compactNoSpace.count { ch ->
            Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN ||
                (ch.code in 0x3040..0x30FF) ||
                (ch.code in 0xAC00..0xD7AF)
        }
        val mostlyCjkCrumb = compactNoSpace.isNotEmpty() && cjkInCompact * 2 >= compactNoSpace.length
        if (mostlyCjkCrumb) {
            if (compactNoSpace.length <= 1) return true
        } else if (compactNoSpace.length <= 2) {
            return true
        }
        val contentLen = strippedBrackets.replace(Regex("""[\s\p{Punct}]+"""), "").length
        if (mostlyCjkCrumb) {
            if (contentLen <= 1) return true
        } else if (contentLen <= 2) {
            return true
        }
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
     * Enough real phrase content to bother with ML Kit MT.
     * Language-agnostic: crumbs skip MT (nonsense target), real short speech still passes.
     * CJK family: ≥2 content chars. Latin/other: ≥2 tokens or ≥6 letters.
     */
    fun hasEnoughContentForMt(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || isJunk(t)) return false
        val stripped = t
            .replace(Regex("""[\[\]{}()<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val normalized = normalize(stripped)
        if (normalized.isEmpty()) return false
        val compact = normalized.replace(Regex("""\s+"""), "")
        return when (scriptFamilyOf(t)) {
            ScriptFamily.CJK -> compact.length >= 2
            else -> {
                val tokens = normalized.split(' ').filter { it.isNotEmpty() }
                tokens.size >= 2 || compact.length >= 6
            }
        }
    }

    /**
     * True when [a] and [b] are the same caption or a near-duplicate (containment)
     * after normalize — used to suppress re-publishing within a short window.
     */
    fun isNearDuplicate(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val shorter = if (na.length <= nb.length) na else nb
        val longer = if (na.length <= nb.length) nb else na
        // Phrase extension (partial window → fuller phrase): NOT a duplicate — keep fuller text.
        // Language-agnostic fix for "parts and pieces" / dropped words across Live windows.
        if (longer.length >= shorter.length + 6 &&
            (longer.startsWith(shorter) || longer.endsWith(shorter))
        ) {
            return false
        }
        // Short containment / filler drift: suppress only when lengths are similar.
        if (na.length <= 48 && nb.length <= 48) {
            val lenRatio = longer.length.toDouble() / shorter.length.coerceAtLeast(1)
            if (lenRatio <= 1.25) {
                if (na.startsWith(nb) || nb.startsWith(na)) return true
                if (na.endsWith(nb) || nb.endsWith(na)) return true
            }
        }
        return false
    }

    /** Dominant writing system of [text] — used for language-agnostic MT/display gates. */
    enum class ScriptFamily { CJK, LATIN, OTHER, EMPTY }

    fun scriptFamilyOf(text: String): ScriptFamily = dominantFamily(countScriptLetters(text))

    /** Expected script family for a BCP-47-ish language code (language-agnostic table). */
    fun scriptFamilyForLang(code: String): ScriptFamily {
        val n = code.trim().lowercase()
            .removePrefix("<|").removeSuffix("|>")
            .substringBefore('-').substringBefore('_')
        if (n.isEmpty() || n == "auto" || n == "und" || n == "unknown") return ScriptFamily.EMPTY
        return when (n) {
            "zh", "ja", "ko", "yue", "cmn", "wuu", "nan" -> ScriptFamily.CJK
            "ar", "he", "fa", "ur", "yi" -> ScriptFamily.OTHER
            "ru", "uk", "bg", "sr", "mk", "be" -> ScriptFamily.OTHER
            "th", "hi", "bn", "ta", "te", "ml", "kn", "gu", "pa", "my", "km", "lo" -> ScriptFamily.OTHER
            "el", "ka", "hy", "am" -> ScriptFamily.OTHER
            else -> ScriptFamily.LATIN // en/es/fr/de/pt/it/nl/… and unknown Latin-script codes
        }
    }

    /**
     * True when painting ASR [sourceText] as primary would show the wrong script for [targetLang].
     * Language-agnostic: dominant mismatch OR significant mixed foreign glyphs → hold for MT.
     * Fixes majority misclassify (e.g. long Latin + a few CJK chars still flashes non-target script).
     */
    fun shouldHoldSourceOffOverlay(sourceText: String, targetLang: String): Boolean {
        val tgt = scriptFamilyForLang(targetLang)
        if (tgt == ScriptFamily.EMPTY) return false
        val counts = countScriptLetters(sourceText)
        val letters = counts.cjk + counts.latin + counts.other
        if (letters == 0) return false
        val src = dominantFamily(counts)
        if (src != ScriptFamily.EMPTY && src != tgt) return true
        // Mixed-script: significant foreign letters even when majority matches target.
        return when (tgt) {
            ScriptFamily.LATIN -> counts.cjk >= 2 || counts.other >= 3
            ScriptFamily.CJK -> counts.other >= 3
            ScriptFamily.OTHER -> counts.cjk >= 2
            ScriptFamily.EMPTY -> false
        }
    }

    private data class ScriptCounts(val cjk: Int, val latin: Int, val other: Int)

    private fun countScriptLetters(text: String): ScriptCounts {
        var cjk = 0
        var latin = 0
        var other = 0
        for (ch in text) {
            when {
                Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN ||
                    ch.code in 0x3040..0x30FF ||
                    ch.code in 0xAC00..0xD7AF -> cjk++
                ch in 'A'..'Z' || ch in 'a'..'z' ||
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.LATIN -> latin++
                ch.isLetter() -> other++
            }
        }
        return ScriptCounts(cjk, latin, other)
    }

    private fun dominantFamily(c: ScriptCounts): ScriptFamily {
        val letters = c.cjk + c.latin + c.other
        if (letters == 0) return ScriptFamily.EMPTY
        return when {
            c.cjk * 2 >= letters -> ScriptFamily.CJK
            c.latin * 2 >= letters -> ScriptFamily.LATIN
            c.other * 2 >= letters -> ScriptFamily.OTHER
            c.cjk >= c.latin && c.cjk >= c.other -> ScriptFamily.CJK
            c.latin >= c.other -> ScriptFamily.LATIN
            else -> ScriptFamily.OTHER
        }
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
