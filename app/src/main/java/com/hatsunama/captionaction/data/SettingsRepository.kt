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
        val setupComplete = booleanPreferencesKey("setup_complete")
        val permissionsWalkthrough = booleanPreferencesKey("permissions_walkthrough_complete")
        val modelTier = stringPreferencesKey("model_tier")
        val targetLang = stringPreferencesKey("target_lang")
        val passthrough = stringPreferencesKey("passthrough")
        val dual = booleanPreferencesKey("dual_subtitles")
        val overlayX = intPreferencesKey("overlay_x")
        val overlayY = intPreferencesKey("overlay_y")
        val overlayW = intPreferencesKey("overlay_w")
        val overlayH = intPreferencesKey("overlay_h")
        val fontIndex = intPreferencesKey("font_index")
        val themeIndex = intPreferencesKey("theme_index")
        val preferPlayback = booleanPreferencesKey("prefer_playback_capture")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            setupComplete = p[Keys.setupComplete] ?: false,
            permissionsWalkthroughComplete = p[Keys.permissionsWalkthrough] ?: false,
            modelTierId = p[Keys.modelTier] ?: ModelTier.BALANCED.id,
            targetLanguage = p[Keys.targetLang] ?: "en",
            passthroughLanguages = (p[Keys.passthrough] ?: "en")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
            dualSubtitles = p[Keys.dual] ?: false,
            overlayX = p[Keys.overlayX] ?: 48,
            overlayY = p[Keys.overlayY] ?: 200,
            overlayWidth = p[Keys.overlayW] ?: 900,
            overlayHeight = p[Keys.overlayH] ?: 180,
            fontIndex = p[Keys.fontIndex] ?: 0,
            themeIndex = p[Keys.themeIndex] ?: 0,
            preferPlaybackCapture = p[Keys.preferPlayback] ?: true
        )
    }

    suspend fun current(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val cur = current()
        val next = transform(cur)
        context.dataStore.edit { p ->
            p[Keys.setupComplete] = next.setupComplete
            p[Keys.permissionsWalkthrough] = next.permissionsWalkthroughComplete
            p[Keys.modelTier] = next.modelTierId
            p[Keys.targetLang] = next.targetLanguage
            p[Keys.passthrough] = next.passthroughLanguages.joinToString(",")
            p[Keys.dual] = next.dualSubtitles
            p[Keys.overlayX] = next.overlayX
            p[Keys.overlayY] = next.overlayY
            p[Keys.overlayW] = next.overlayWidth
            p[Keys.overlayH] = next.overlayHeight
            p[Keys.fontIndex] = next.fontIndex
            p[Keys.themeIndex] = next.themeIndex
            p[Keys.preferPlayback] = next.preferPlaybackCapture
        }
    }
}
