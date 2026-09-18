package com.hatsunama.captionaction.inference

/**
 * Soft-wraps live caption text for the overlay. Does **not** ellipsize or hard-cap to 2 lines —
 * the primary TextView must show the full current caption (wrap within overlay height).
 */
class SubtitleComposer(
    private val maxCharsPerLine: Int = 40,
    /** Soft wrap line budget; high enough for full sentences in the overlay. */
    private val maxLines: Int = 28,
    /** Rolling buffer of recent text (chars); keeps last utterances readable. */
    private val maxBufferChars: Int = 560
) {
    private var current = StringBuilder()
    private var lastUpdate = 0L

    fun compose(incoming: String, nowMs: Long = System.currentTimeMillis()): String {
        val cleaned = incoming.trim()
        if (cleaned.isEmpty()) return display()

        if (nowMs - lastUpdate > 3500L) {
            current = StringBuilder()
        }
        lastUpdate = nowMs

        if (current.isNotEmpty()) current.append(' ')
        current.append(cleaned)

        if (current.length > maxBufferChars) {
            val s = current.toString()
            // Trim at a word boundary near the limit so we don't start mid-word.
            var cut = s.length - maxBufferChars
            val space = s.indexOf(' ', cut).let { if (it < 0) cut else it + 1 }
            current = StringBuilder(s.substring(space.coerceIn(0, s.length)))
        }
        return display()
    }

    fun display(): String {
        val words = current.toString().trim().split(Regex("\\s+"))
        if (words.isEmpty() || words.first().isEmpty()) return ""
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (w in words) {
            if (line.isNotEmpty() && line.length + 1 + w.length > maxCharsPerLine) {
                lines.add(line.toString())
                line = StringBuilder(w)
                if (lines.size >= maxLines) {
                    // Keep newest lines if we somehow exceed budget.
                    while (lines.size >= maxLines) lines.removeAt(0)
                }
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
        }
        if (line.isNotEmpty()) {
            if (lines.size >= maxLines) lines.removeAt(0)
            lines.add(line.toString())
        }
        return lines.joinToString("\n")
    }

    fun reset() {
        current = StringBuilder()
        lastUpdate = 0L
    }
}
