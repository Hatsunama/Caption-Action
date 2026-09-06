package com.hatsunama.captionaction.inference

/**
 * Merges short fragments into stable, readable caption lines.
 */
class SubtitleComposer(
    private val maxCharsPerLine: Int = 42,
    private val maxLines: Int = 2
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

        // Soft trim to avoid runaway length
        if (current.length > maxCharsPerLine * maxLines * 2) {
            val s = current.toString()
            current = StringBuilder(s.takeLast(maxCharsPerLine * maxLines))
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
                if (lines.size >= maxLines) break
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
        }
        if (lines.size < maxLines && line.isNotEmpty()) lines.add(line.toString())
        return lines.take(maxLines).joinToString("\n")
    }

    fun reset() {
        current = StringBuilder()
        lastUpdate = 0L
    }
}
