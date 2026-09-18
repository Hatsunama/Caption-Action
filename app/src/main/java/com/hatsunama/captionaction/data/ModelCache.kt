package com.hatsunama.captionaction.data

import android.content.Context
import java.io.File

class ModelCache(context: Context) {
    private val root = File(context.filesDir, "models").also { it.mkdirs() }

    fun fileFor(tier: ModelTier): File = File(root, tier.fileName)

    /** Partial download for [tier] only — never shared across tiers. */
    fun partFileFor(tier: ModelTier): File = File(root, tier.fileName + ".part")

    /**
     * Completed model ready for use. Requires size near [ModelTier.approxBytes]
     * so tiny/corrupt/incomplete files are never treated as ready.
     * No content hash is stored yet; size gate is the validation.
     */
    fun isPresent(tier: ModelTier): Boolean {
        val f = fileFor(tier)
        if (!f.exists()) return false
        return f.length() >= minReadyBytes(tier)
    }

    /** True when a non-empty .part exists and the final file is not ready. */
    fun isPartial(tier: ModelTier): Boolean {
        if (isPresent(tier)) return false
        val part = partFileFor(tier)
        return part.exists() && part.length() > 0L
    }

    fun partialBytes(tier: ModelTier): Long {
        val part = partFileFor(tier)
        return if (part.exists()) part.length() else 0L
    }

    fun deletePartial(tier: ModelTier) {
        partFileFor(tier).delete()
    }

    /** Usable for on-device ASR: Fast SenseVoice OR ready ggml Balanced/Accurate. */
    fun isReadyForAsr(tier: ModelTier): Boolean = isPresent(tier)

    fun freeBytes(): Long = root.usableSpace

    companion object {
        /** At least 90% of advertised size, floor 1 MiB. */
        fun minReadyBytes(tier: ModelTier): Long =
            maxOf(1_000_000L, (tier.approxBytes * 9L) / 10L)
    }
}
