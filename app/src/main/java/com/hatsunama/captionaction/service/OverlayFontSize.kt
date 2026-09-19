package com.hatsunama.captionaction.service

import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic caption type for the seeker overlay bubble.
 *
 * Tall bubble + short text → larger sp (use spare room).
 * Longer text → scale down so wrapped captions still fit without absurd giants.
 * Prefer fitting into the current bubble over only growing height with tiny type.
 */
object OverlayFontSize {
    const val MIN_SP = 12f
    const val MAX_SP = 26f

    /** Secondary line size relative to primary. */
    const val SECONDARY_RATIO = 0.85f

    /**
     * Height-based ceiling: min ~180dp bubble → ~13sp room; tall ~350dp → ~26sp.
     * Flatter than /14 (old 0.3.5) but well above the 0.3.13 hard cap of 16sp.
     */
    fun heightCeilingSp(heightPx: Int, density: Float): Float {
        if (density <= 0f || heightPx <= 0) return MIN_SP
        val heightDp = heightPx / density
        return (heightDp / 13.5f).coerceIn(MIN_SP, MAX_SP)
    }

    /**
     * Content-density factor in ~[0.48, 1]: short captions keep full ceiling;
     * long dual captions pull toward [MIN_SP].
     */
    fun contentDensityFactor(primaryChars: Int, secondaryChars: Int = 0): Float {
        val total = primaryChars + (secondaryChars * SECONDARY_RATIO).toInt()
        return when {
            total <= 40 -> 1f
            total >= 260 -> 0.48f
            else -> 1f - (total - 40) / (260f - 40f) * 0.52f
        }
    }

    /**
     * Target primary sp from height + content density (no measure).
     */
    fun computePrimarySp(
        heightPx: Int,
        density: Float,
        primaryChars: Int,
        secondaryChars: Int = 0
    ): Float {
        val ceiling = heightCeilingSp(heightPx, density)
        val factor = contentDensityFactor(primaryChars, secondaryChars)
        return (ceiling * factor).coerceIn(MIN_SP, MAX_SP)
    }

    /**
     * Largest primary sp in [MIN_SP, ceiling] that is estimated to fit the
     * current bubble width×height. Binary search; falls back to [computePrimarySp]
     * when dimensions are missing.
     *
     * Chrome matches [overlay_caption]: bubble margins/padding + optional status row.
     */
    fun largestFittingSp(
        heightPx: Int,
        widthPx: Int,
        density: Float,
        primaryChars: Int,
        secondaryChars: Int = 0,
        statusVisible: Boolean = false,
        cjkHeavy: Boolean = false
    ): Float {
        val ceiling = min(
            heightCeilingSp(heightPx, density),
            computePrimarySp(heightPx, density, primaryChars, secondaryChars)
        )
        if (density <= 0f || widthPx <= 0 || heightPx <= 0) {
            return ceiling
        }
        if (primaryChars < 1) return ceiling

        val contentWdp = contentWidthDp(widthPx, density)
        val contentHdp = contentHeightDp(heightPx, density, statusVisible, secondaryChars > 0)
        if (contentWdp < 40f || contentHdp < 40f) return MIN_SP

        var lo = MIN_SP
        var hi = ceiling
        var best = MIN_SP
        // 8 iterations ≈ 0.05sp resolution across a ~14sp range.
        repeat(8) {
            val mid = (lo + hi) * 0.5f
            if (fits(mid, contentWdp, contentHdp, primaryChars, secondaryChars, cjkHeavy)) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        return best.coerceIn(MIN_SP, MAX_SP)
    }

    /** Bubble inner text width in dp (margins start/end + padding). */
    fun contentWidthDp(widthPx: Int, density: Float): Float {
        // margins 16+20, padding 14*2 → 64dp chrome horizontally
        return (widthPx / density) - 64f
    }

    /**
     * Bubble inner text height budget in dp for primary (+ secondary if present).
     * Margins 16+12, padding 14*2, optional status ~22, gaps ~8.
     */
    fun contentHeightDp(
        heightPx: Int,
        density: Float,
        statusVisible: Boolean,
        secondaryVisible: Boolean
    ): Float {
        var chrome = 16f + 12f + 28f + 8f // margins + padding + small gaps
        if (statusVisible) chrome += 22f
        // Secondary height is counted in [fits], not as fixed chrome.
        if (secondaryVisible) chrome += 4f // marginTop on secondary
        return (heightPx / density) - chrome
    }

    fun fits(
        primarySp: Float,
        contentWdp: Float,
        contentHdp: Float,
        primaryChars: Int,
        secondaryChars: Int,
        cjkHeavy: Boolean
    ): Boolean {
        val glyphEm = if (cjkHeavy) 1.0f else 0.58f
        val primaryBlock = estimateBlockDp(primaryChars, contentWdp, primarySp, glyphEm)
        val secondaryBlock =
            if (secondaryChars > 0) {
                estimateBlockDp(
                    secondaryChars,
                    contentWdp,
                    primarySp * SECONDARY_RATIO,
                    glyphEm
                )
            } else {
                0f
            }
        return primaryBlock + secondaryBlock <= contentHdp
    }

    fun estimateBlockDp(
        chars: Int,
        widthDp: Float,
        sp: Float,
        glyphEm: Float,
        lineHeightMult: Float = 1.28f
    ): Float {
        if (chars <= 0 || sp <= 0f) return 0f
        val cpl = max(1, (widthDp / (sp * glyphEm)).toInt())
        val lines = max(1, (chars + cpl - 1) / cpl)
        return lines * sp * lineHeightMult
    }

    /** True when CJK/Hiragana/Katakana/Hangul dominate the sample. */
    fun isCjkHeavy(text: String): Boolean {
        if (text.isEmpty()) return false
        var cjk = 0
        var letters = 0
        for (ch in text) {
            when {
                ch.isWhitespace() -> {}
                ch in '\u4E00'..'\u9FFF' ||
                    ch in '\u3040'..'\u30FF' ||
                    ch in '\uAC00'..'\uD7AF' ||
                    ch in '\u3400'..'\u4DBF' -> {
                    cjk++
                    letters++
                }
                ch.isLetter() -> letters++
            }
        }
        if (letters == 0) return false
        return cjk * 2 >= letters // ≥50% CJK among letters
    }
}
