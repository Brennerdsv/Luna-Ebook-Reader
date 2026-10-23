package com.luna.reader.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.luna.reader.tts.AvailableVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "luna_user_settings")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val SELECTED_OFFLINE_VOICE = stringPreferencesKey("selected_offline_voice")
        val SELECTED_VOICE = stringPreferencesKey("selected_voice")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val FONT_SCALE = floatPreferencesKey("font_scale")
    }

    val selectedVoiceFlow: Flow<AvailableVoice> = context.dataStore.data.map { preferences ->
        val voiceId = preferences[PreferencesKeys.SELECTED_OFFLINE_VOICE]
            ?: preferences[PreferencesKeys.SELECTED_VOICE]
            ?: AvailableVoice.DEFAULT.id
        AvailableVoice.fromId(voiceId)
    }

    val playbackSpeedFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PLAYBACK_SPEED] ?: 1.0f
    }

    val fontScaleFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.FONT_SCALE] ?: 1.0f
    }

    suspend fun setSelectedVoice(voice: AvailableVoice) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_OFFLINE_VOICE] = voice.id
            preferences[PreferencesKeys.SELECTED_VOICE] = voice.id
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PLAYBACK_SPEED] = speed
        }
    }

    suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_SCALE] = scale
        }
    }
}
