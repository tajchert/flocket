package com.mtajchert.flocket.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persisted shape of the user preferences; `core:data` maps it to its public `UserPreferences`. */
data class StoredPreferences(
    val onboardingCompleted: Boolean = false,
    /** Name of the theme enum entry; unknown values fall back to the default in the mapper. */
    val theme: String = "System",
    val dynamicColor: Boolean = true,
    val terminalFontSize: Float = 13f,
    val keepScreenOnInTerminal: Boolean = true,
    val showExtraKeys: Boolean = true,
    val notificationPermissionAsked: Boolean = false,
    val keepConnectedInBackground: Boolean = true,
)

/** Reads and writes [StoredPreferences] on a Preferences DataStore. Unreadable files reset to defaults. */
class PreferencesDataSource(
    private val store: DataStore<Preferences>,
) {
    val preferences: Flow<StoredPreferences> =
        store.data.map { p ->
            val d = StoredPreferences()
            StoredPreferences(
                onboardingCompleted = p[ONBOARDING] ?: d.onboardingCompleted,
                theme = p[THEME] ?: d.theme,
                dynamicColor = p[DYNAMIC_COLOR] ?: d.dynamicColor,
                terminalFontSize = p[FONT_SIZE] ?: d.terminalFontSize,
                keepScreenOnInTerminal = p[KEEP_SCREEN_ON] ?: d.keepScreenOnInTerminal,
                showExtraKeys = p[EXTRA_KEYS] ?: d.showExtraKeys,
                notificationPermissionAsked = p[NOTIFICATIONS_ASKED] ?: d.notificationPermissionAsked,
                keepConnectedInBackground = p[KEEP_CONNECTED] ?: d.keepConnectedInBackground,
            )
        }

    /** Atomic read-modify-write. */
    suspend fun update(transform: (StoredPreferences) -> StoredPreferences) {
        store.edit { p ->
            val current =
                StoredPreferences(
                    onboardingCompleted = p[ONBOARDING] ?: false,
                    theme = p[THEME] ?: "System",
                    dynamicColor = p[DYNAMIC_COLOR] ?: true,
                    terminalFontSize = p[FONT_SIZE] ?: 13f,
                    keepScreenOnInTerminal = p[KEEP_SCREEN_ON] ?: true,
                    showExtraKeys = p[EXTRA_KEYS] ?: true,
                    notificationPermissionAsked = p[NOTIFICATIONS_ASKED] ?: false,
                    keepConnectedInBackground = p[KEEP_CONNECTED] ?: true,
                )
            val n = transform(current)
            p[ONBOARDING] = n.onboardingCompleted
            p[THEME] = n.theme
            p[DYNAMIC_COLOR] = n.dynamicColor
            p[FONT_SIZE] = n.terminalFontSize
            p[KEEP_SCREEN_ON] = n.keepScreenOnInTerminal
            p[EXTRA_KEYS] = n.showExtraKeys
            p[NOTIFICATIONS_ASKED] = n.notificationPermissionAsked
            p[KEEP_CONNECTED] = n.keepConnectedInBackground
        }
    }

    private companion object {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FONT_SIZE = floatPreferencesKey("terminal_font_size")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_in_terminal")
        val EXTRA_KEYS = booleanPreferencesKey("show_extra_keys")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notification_permission_asked")
        val KEEP_CONNECTED = booleanPreferencesKey("keep_connected_in_background")
    }
}
