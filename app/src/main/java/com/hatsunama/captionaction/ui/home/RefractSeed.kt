package com.hatsunama.captionaction.ui.home

import android.content.Context

/** Compatibility facade; canonical implementation retains the existing preference keys. */
object RefractSeed {
    fun obtain(context: Context): Long = com.hatsunama.refract.RefractSeed.obtain(context)
    fun hashToSeed(input: String): Long = com.hatsunama.refract.RefractSeed.hashToSeed(input)
}
