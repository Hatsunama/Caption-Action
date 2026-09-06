package com.hatsunama.captionaction.data

import android.content.Context
import java.io.File

class ModelCache(context: Context) {
    private val root = File(context.filesDir, "models").also { it.mkdirs() }

    fun fileFor(tier: ModelTier): File = File(root, tier.fileName)

    fun isPresent(tier: ModelTier): Boolean {
        val f = fileFor(tier)
        return f.exists() && f.length() > 1_000_000L
    }

    fun freeBytes(): Long = root.usableSpace
}
