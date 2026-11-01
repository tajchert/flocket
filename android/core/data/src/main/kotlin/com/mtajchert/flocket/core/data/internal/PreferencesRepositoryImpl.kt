package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.appResult
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.data.ThemePreference
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.datastore.PreferencesDataSource
import com.mtajchert.flocket.core.datastore.StoredPreferences
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
            terminalFontSize = terminalFontSize,
            keepScreenOnInTerminal = keepScreenOnInTerminal,
            showExtraKeys = showExtraKeys,
        )

    private fun UserPreferences.toStored() =
        StoredPreferences(onboardingCompleted, theme.name, dynamicColor, terminalFontSize, keepScreenOnInTerminal, showExtraKeys)
}
