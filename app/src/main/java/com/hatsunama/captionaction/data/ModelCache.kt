package com.hatsunama.captionaction.data

import android.content.Context
import java.io.File

class ModelCache(context: Context) {
    private val root = File(context.filesDir, "models").also { it.mkdirs() }

    fun fileFor(tier: ModelTier): File = File(root, tier.fileName)

    fun partFileFor(tier: ModelTier): File = File(root, tier.fileName + ".part")

    fun isPresent(tier: ModelTier): Boolean {
        val f = fileFor(tier)
        if (!f.exists()) return false
        return f.length() >= minReadyBytes(tier)
    }

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

    fun isReadyForAsr(tier: ModelTier): Boolean = isPresent(tier)

    fun freeBytes(): Long = root.usableSpace

    companion object {
        fun minReadyBytes(tier: ModelTier): Long =
            maxOf(1_000_000L, (tier.approxBytes * 9L) / 10L)
    }
}
