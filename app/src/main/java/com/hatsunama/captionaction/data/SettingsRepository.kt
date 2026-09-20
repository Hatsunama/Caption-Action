package com.hatsunama.captionaction.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "caption_action_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val permissionsWalkthrough = booleanPreferencesKey("permissions_walkthrough_complete")
        val modelTier = stringPreferencesKey("model_tier")
        val inputLang = stringPreferencesKey("input_lang")
        val targetLang = stringPreferencesKey("target_lang")
        /** Legacy key — read once for inputLanguage migration; product no longer writes a set. */
        val passthrough = stringPreferencesKey("passthrough")
        val dual = booleanPreferencesKey("dual_subtitles")
        val saveSubtitlesToFile = booleanPreferencesKey("save_subtitles_to_file")
        val overlayX = intPreferencesKey("overlay_x")
        val overlayY = intPreferencesKey("overlay_y")
        val overlayW = intPreferencesKey("overlay_w")
        val overlayH = intPreferencesKey("overlay_h")
        val fontIndex = intPreferencesKey("font_index")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            permissionsWalkthroughComplete = p[Keys.permissionsWalkthrough] ?: false,
            modelTierId = p[Keys.modelTier] ?: ModelTier.BALANCED.id,
            inputLanguage = resolveInputLanguage(p),
            targetLanguage = p[Keys.targetLang] ?: "en",
            passthroughLanguages = emptySet(),
            dualSubtitles = p[Keys.dual] ?: false,
            saveSubtitlesToFile = p[Keys.saveSubtitlesToFile] ?: false,
            overlayX = p[Keys.overlayX] ?: 48,
            overlayY = p[Keys.overlayY] ?: 200,
            overlayWidth = p[Keys.overlayW] ?: 900,
            overlayHeight = p[Keys.overlayH] ?: 380,
            fontIndex = p[Keys.fontIndex] ?: 0
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val cur = current()
        val next = transform(cur)
        context.dataStore.edit { p ->
            p[Keys.permissionsWalkthrough] = next.permissionsWalkthroughComplete
            p[Keys.modelTier] = next.modelTierId
            p[Keys.inputLang] = next.inputLanguage
            p[Keys.targetLang] = next.targetLanguage
            // Stop writing product passthrough sets (empty OK for legacy readers).
            p[Keys.passthrough] = ""
            p[Keys.dual] = next.dualSubtitles
            p[Keys.saveSubtitlesToFile] = next.saveSubtitlesToFile
            p[Keys.overlayX] = next.overlayX
            p[Keys.overlayY] = next.overlayY
            p[Keys.overlayW] = next.overlayWidth
            p[Keys.overlayH] = next.overlayHeight
            p[Keys.fontIndex] = next.fontIndex
        }
    }

    companion object {
        /**
         * Prefer stored input_lang; else migrate from old passthrough (single code → that, else en).
         */
        internal fun resolveInputLanguage(p: Preferences): String {
            val stored = p[Keys.inputLang]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (stored != null) return stored
            val oldPass = (p[Keys.passthrough] ?: "")
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            return if (oldPass.size == 1) oldPass.first() else "en"
        }
    }
}
