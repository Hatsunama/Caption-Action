package com.hatsunama.captionaction.service

import com.hatsunama.captionaction.inference.AsrJunkFilter

/** Session-local duplicate protection; a repeated phrase is valid again after a pause. */
internal class CaptionDuplicateGate(private val windowMs: Long = 2_500L) {
    private var lastNorm = ""
    private var publishedAtMs = 0L

    fun shouldSuppress(norm: String, nowMs: Long): Boolean {
        val ageMs = nowMs - publishedAtMs
        return norm.isNotEmpty() && lastNorm.isNotEmpty() &&
            ageMs in 0..windowMs &&
            (norm == lastNorm || AsrJunkFilter.isNearDuplicate(norm, lastNorm))
    }

    // Suppressed candidates never extend the expiry, otherwise a repeated phrase
    // could keep its own suppression alive forever.
    fun onPublished(norm: String, nowMs: Long) {
        lastNorm = norm
        publishedAtMs = nowMs
    }
}
