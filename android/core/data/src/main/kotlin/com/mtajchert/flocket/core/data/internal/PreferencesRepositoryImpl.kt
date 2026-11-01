package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.appResult
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.data.ThemePreference
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.datastore.PreferencesDataSource
import com.mtajchert.flocket.core.datastore.StoredPreferences
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class PreferencesRepositoryImpl(
    private val source: PreferencesDataSource,
) : PreferencesRepository {
    override val preferences: Flow<UserPreferences> = source.preferences.map { it.toModel() }.distinctUntilChanged()

    override suspend fun update(transform: (UserPreferences) -> UserPreferences): AppResult<Unit> =
        appResult { source.update { transform(it.toModel()).toStored() } }

    private fun StoredPreferences.toModel() =
        UserPreferences(
            onboardingCompleted = onboardingCompleted,
            theme = ThemePreference.entries.firstOrNull { it.name == theme } ?: ThemePreference.System,
            dynamicColor = dynamicColor,
            terminal =
                TerminalPreferences(
                    fontSizeSp = terminalFontSize,
                    colorScheme = enumOrDefault(terminalColorScheme, TerminalColorScheme.FollowApp),
                    showExtraKeys = showExtraKeys,
                    keepScreenOn = keepScreenOnInTerminal,
                    clipboardWritePolicy = enumOrDefault(clipboardWritePolicy, ClipboardWritePolicy.Ask),
                    bell = enumOrDefault(bell, BellMode.Haptic),
                    autoConnect = autoConnect,
                    defaultWorkingDirectory =
                        defaultWorkingDirectory.ifBlank { TerminalPreferences.DEFAULT_WORKING_DIRECTORY },
                ),
            notificationPermissionAsked = notificationPermissionAsked,
            keepConnectedInBackground = keepConnectedInBackground,
        )

    private fun UserPreferences.toStored() =
        StoredPreferences(
            onboardingCompleted = onboardingCompleted,
            theme = theme.name,
            dynamicColor = dynamicColor,
            terminalFontSize = terminal.fontSizeSp,
            keepScreenOnInTerminal = terminal.keepScreenOn,
            showExtraKeys = terminal.showExtraKeys,
            notificationPermissionAsked = notificationPermissionAsked,
            keepConnectedInBackground = keepConnectedInBackground,
            terminalColorScheme = terminal.colorScheme.name,
            clipboardWritePolicy = terminal.clipboardWritePolicy.name,
            bell = terminal.bell.name,
            autoConnect = terminal.autoConnect,
            defaultWorkingDirectory = terminal.defaultWorkingDirectory,
        )
}

internal inline fun <reified E : Enum<E>> enumOrDefault(
    name: String?,
    default: E,
): E = enumValues<E>().firstOrNull { it.name == name } ?: default
