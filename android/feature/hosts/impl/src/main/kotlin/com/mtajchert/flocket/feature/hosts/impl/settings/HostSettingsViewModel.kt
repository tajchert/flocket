package com.mtajchert.flocket.feature.hosts.impl.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostSettingsRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.HostSetting
import com.mtajchert.flocket.core.model.HostSettingsOverrides
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.resolve
import com.mtajchert.flocket.core.ui.terminalsettings.MAX_TERMINAL_FONT_SP
import com.mtajchert.flocket.core.ui.terminalsettings.MIN_TERMINAL_FONT_SP
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsFormState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HostSettingsUiState(
    val loading: Boolean = true,
    /** Null when the host no longer exists. */
    val hostLabel: String? = null,
    val form: TerminalSettingsFormState =
        TerminalSettingsFormState(
            com.mtajchert.flocket.core.model
                .TerminalPreferences(),
        ),
) {
    val hasOverrides: Boolean get() = !form.overridden.isNullOrEmpty()
}

@HiltViewModel(assistedFactory = HostSettingsViewModel.Factory::class)
class HostSettingsViewModel
    @AssistedInject
    constructor(
        @Assisted hostId: String,
        hosts: HostRepository,
        private val settings: HostSettingsRepository,
        private val preferences: PreferencesRepository,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(hostId: String): HostSettingsViewModel
        }

        private val id = HostId(hostId)

        val uiState: StateFlow<HostSettingsUiState> =
            combine(hosts.hosts, settings.overrides(id), preferences.preferences) { all, overrides, prefs ->
                val host = all.firstOrNull { it.id == id }
                val effective = resolve(prefs.terminal, overrides)
                HostSettingsUiState(
                    loading = false,
                    hostLabel = host?.label,
                    form = TerminalSettingsFormState(effective.terminal, prefs.terminal, effective.overridden),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostSettingsUiState())

        fun setFontSize(size: Float) =
            edit { it.copy(fontSizeSp = size.coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)) }

        fun setColorScheme(scheme: TerminalColorScheme) = edit { it.copy(colorScheme = scheme) }

        fun setShowExtraKeys(enabled: Boolean) = edit { it.copy(showExtraKeys = enabled) }

        fun setKeepScreenOn(enabled: Boolean) = edit { it.copy(keepScreenOn = enabled) }

        fun setClipboardPolicy(policy: ClipboardWritePolicy) = edit { it.copy(clipboardWritePolicy = policy) }

        fun setBell(bell: BellMode) = edit { it.copy(bell = bell) }

        fun setAutoConnect(enabled: Boolean) = edit { it.copy(autoConnect = enabled) }

        /** A blank folder means "use the app default", not an empty override. */
        fun setWorkingDirectory(path: String) = edit { it.copy(defaultWorkingDirectory = path.trim().ifEmpty { null }) }

        /** `true`: follow the app default again. `false`: start customizing from the current app default. */
        fun setUseAppDefault(
            setting: HostSetting,
            useDefault: Boolean,
        ) {
            viewModelScope.launch {
                val app = preferences.preferences.first().terminal
                settings.update(id) { if (useDefault) it.cleared(setting) else it.customized(setting, app) }
            }
        }

        fun resetAll() {
            viewModelScope.launch { settings.resetAll(id) }
        }

        private fun edit(transform: (HostSettingsOverrides) -> HostSettingsOverrides) {
            viewModelScope.launch { settings.update(id, transform) }
        }
    }
