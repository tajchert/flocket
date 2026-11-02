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
    /** Enum entry names; unknown values fall back to the defaults in the mapper. */
    val terminalColorScheme: String = "FollowApp",
    val clipboardWritePolicy: String = "Ask",
    val bell: String = "Haptic",
    val autoConnect: Boolean = true,
    val defaultWorkingDirectory: String = "~",
    /** Terminal list order (enum entry name); unknown or missing values mean Priority in the mapper. */
    val terminalListSort: String = "Inbox",
)

/** Reads and writes [StoredPreferences] on a Preferences DataStore. Unreadable files reset to defaults. */
class PreferencesDataSource(
    private val store: DataStore<Preferences>,
) {
    val preferences: Flow<StoredPreferences> = store.data.map { it.toStored() }

    /** Atomic read-modify-write. */
    suspend fun update(transform: (StoredPreferences) -> StoredPreferences) {
        store.edit { p ->
            val n = transform(p.toStored())
            p[ONBOARDING] = n.onboardingCompleted
            p[THEME] = n.theme
            p[DYNAMIC_COLOR] = n.dynamicColor
            p[FONT_SIZE] = n.terminalFontSize
            p[KEEP_SCREEN_ON] = n.keepScreenOnInTerminal
            p[EXTRA_KEYS] = n.showExtraKeys
            p[NOTIFICATIONS_ASKED] = n.notificationPermissionAsked
            p[KEEP_CONNECTED] = n.keepConnectedInBackground
            p[COLOR_SCHEME] = n.terminalColorScheme
            p[CLIPBOARD_POLICY] = n.clipboardWritePolicy
            p[BELL] = n.bell
            p[AUTO_CONNECT] = n.autoConnect
            p[DEFAULT_CWD] = n.defaultWorkingDirectory
            p[LIST_SORT] = n.terminalListSort
        }
    }

    private fun Preferences.toStored(): StoredPreferences {
        val d = StoredPreferences()
        return StoredPreferences(
            onboardingCompleted = this[ONBOARDING] ?: d.onboardingCompleted,
            theme = this[THEME] ?: d.theme,
            dynamicColor = this[DYNAMIC_COLOR] ?: d.dynamicColor,
            terminalFontSize = this[FONT_SIZE] ?: d.terminalFontSize,
            keepScreenOnInTerminal = this[KEEP_SCREEN_ON] ?: d.keepScreenOnInTerminal,
            showExtraKeys = this[EXTRA_KEYS] ?: d.showExtraKeys,
            notificationPermissionAsked = this[NOTIFICATIONS_ASKED] ?: d.notificationPermissionAsked,
            keepConnectedInBackground = this[KEEP_CONNECTED] ?: d.keepConnectedInBackground,
            terminalColorScheme = this[COLOR_SCHEME] ?: d.terminalColorScheme,
            clipboardWritePolicy = this[CLIPBOARD_POLICY] ?: d.clipboardWritePolicy,
            bell = this[BELL] ?: d.bell,
            autoConnect = this[AUTO_CONNECT] ?: d.autoConnect,
            defaultWorkingDirectory = this[DEFAULT_CWD] ?: d.defaultWorkingDirectory,
            terminalListSort = this[LIST_SORT] ?: d.terminalListSort,
        )
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
        val COLOR_SCHEME = stringPreferencesKey("terminal_color_scheme")
        val CLIPBOARD_POLICY = stringPreferencesKey("clipboard_write_policy")
        val BELL = stringPreferencesKey("terminal_bell")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val DEFAULT_CWD = stringPreferencesKey("default_working_directory")
        val LIST_SORT = stringPreferencesKey("terminal_list_sort")
    }
}
