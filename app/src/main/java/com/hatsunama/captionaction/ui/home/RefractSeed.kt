package com.hatsunama.captionaction.ui.home

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Per-install seed for the locked Home refract background.
 * Prefer hashed [Settings.Secure.ANDROID_ID]; fall back to a first-run UUID
 * stored in SharedPreferences when ANDROID_ID is missing/broken.
 */
object RefractSeed {
    private const val PREFS = "refract_bg"
    private const val KEY_SEED = "seed"
    /** Known broken emulator ANDROID_ID — treat as absent. */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    fun obtain(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SEED)) {
            return prefs.getLong(KEY_SEED, 0L)
        }
        val androidId = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val seed = if (!androidId.isNullOrBlank() && androidId != BROKEN_ANDROID_ID) {
            hashToSeed(androidId)
        } else {
            hashToSeed(UUID.randomUUID().toString())
        }
        prefs.edit().putLong(KEY_SEED, seed).apply()
        return seed
    }

    /** Stable positive 31-bit seed from an arbitrary string (FNV-1a 64 → fold). */
    fun hashToSeed(input: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset
        for (c in input) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        val folded = h xor (h ushr 32)
        return folded and 0x7fff_ffffL
    }
}
