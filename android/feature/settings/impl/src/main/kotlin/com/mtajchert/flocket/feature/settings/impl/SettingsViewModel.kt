package com.mtajchert.flocket.feature.settings.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.data.ThemePreference
import com.mtajchert.flocket.core.data.UserPreferences
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.ui.component.ImportKeyDialogState
import com.mtajchert.flocket.core.ui.component.KeySource
import com.mtajchert.flocket.core.ui.component.PickedKeyFile
import com.mtajchert.flocket.core.ui.component.importFrom
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiText
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.terminalsettings.MAX_TERMINAL_FONT_SP
import com.mtajchert.flocket.core.ui.terminalsettings.MIN_TERMINAL_FONT_SP
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class KeyItem(
    val key: DeviceKey,
    /** Labels of the hosts that sign in with this key. */
    val usedBy: List<String>,
)

@Immutable
data class SettingsUiState(
    val loaded: Boolean = false,
    val preferences: UserPreferences = UserPreferences(),
    val keys: List<KeyItem> = emptyList(),
)

sealed interface SettingsDialog {
    data class Generate(
        val label: String = DEFAULT_KEY_LABEL,
        val error: UiError? = null,
        val labelMissing: Boolean = false,
        val busy: Boolean = false,
    ) : SettingsDialog

    /** The shared import form; [form] never holds anything but what the dialog shows. */
    data class Import(
        val form: ImportKeyDialogState = ImportKeyDialogState(),
    ) : SettingsDialog

    data class Delete(
        val item: KeyItem,
    ) : SettingsDialog

    companion object {
        const val DEFAULT_KEY_LABEL = "flocket"
    }
}

sealed interface SettingsEvent {
    data class Info(
        val text: UiText,
    ) : SettingsEvent

    data class Failure(
        val error: UiError,
    ) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val preferences: PreferencesRepository,
        private val keyRepository: DeviceKeyRepository,
        hostRepository: HostRepository,
    ) : ViewModel() {
        private val dialogState = MutableStateFlow<SettingsDialog?>(null)
        private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
        val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()
        val dialog: StateFlow<SettingsDialog?> = dialogState

        val uiState: StateFlow<SettingsUiState> =
            combine(preferences.preferences, keyRepository.keys, hostRepository.hosts) { prefs, keys, hosts ->
                SettingsUiState(
                    loaded = true,
                    preferences = prefs,
                    keys =
                        keys.map { key ->
                            KeyItem(
                                key,
                                hosts.filter { (it.auth as? HostAuth.Key)?.keyAlias == key.alias }.map { it.label },
                            )
                        },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        // ---- preferences ------------------------------------------------------------------------

        fun setTheme(theme: ThemePreference) = update { it.copy(theme = theme) }

        fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

        fun setTerminalFontSize(size: Float) =
            updateTerminal { it.copy(fontSizeSp = size.coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }

        fun setColorScheme(scheme: TerminalColorScheme) = updateTerminal { it.copy(colorScheme = scheme) }

        fun setShowExtraKeys(enabled: Boolean) = updateTerminal { it.copy(showExtraKeys = enabled) }

        fun setKeepScreenOn(enabled: Boolean) = updateTerminal { it.copy(keepScreenOn = enabled) }

        fun setClipboardPolicy(policy: ClipboardWritePolicy) = updateTerminal { it.copy(clipboardWritePolicy = policy) }

        fun setBell(bell: BellMode) = updateTerminal { it.copy(bell = bell) }

        fun setAutoConnect(enabled: Boolean) = updateTerminal { it.copy(autoConnect = enabled) }

        /** A blank folder falls back to `~` so new terminals always have a start directory. */
        fun setDefaultWorkingDirectory(path: String) =
            updateTerminal {
                it.copy(defaultWorkingDirectory = path.trim().ifEmpty { TerminalPreferences.DEFAULT_WORKING_DIRECTORY })
            }

        private fun updateTerminal(transform: (TerminalPreferences) -> TerminalPreferences) =
            update { it.copy(terminal = transform(it.terminal)) }

        fun setKeepConnected(enabled: Boolean) = update { it.copy(keepConnectedInBackground = enabled) }

        private fun update(transform: (UserPreferences) -> UserPreferences) {
            viewModelScope.launch {
                (preferences.update(transform) as? AppResult.Failure)?.let {
                    eventChannel.trySend(SettingsEvent.Failure(it.error.toUiError()))
                }
            }
        }

        // ---- keys -------------------------------------------------------------------------------

        fun dismissDialog() {
            dialogState.value = null
        }

        fun requestGenerate() {
            dialogState.value = SettingsDialog.Generate()
        }

        fun onGenerateLabel(label: String) =
            dialogState.update {
                (it as? SettingsDialog.Generate)?.copy(label = label, labelMissing = false)
                    ?: it
            }

        fun generate() {
            val d = dialogState.value as? SettingsDialog.Generate ?: return
            if (d.busy) return
            if (d.label.isBlank()) {
                dialogState.value = d.copy(labelMissing = true)
                return
            }
            dialogState.value = d.copy(busy = true, error = null)
            viewModelScope.launch {
                when (val r = keyRepository.generate(d.label.trim())) {
                    is AppResult.Success -> {
                        dialogState.value = null
                        eventChannel.trySend(
                            SettingsEvent.Info(UiText.Res(R.string.keys_generated, listOf(r.value.label))),
                        )
                    }

                    is AppResult.Failure -> {
                        dialogState.update {
                            (it as? SettingsDialog.Generate)?.copy(busy = false, error = r.error.toUiError())
                                ?: it
                        }
                    }
                }
            }
        }

        fun requestImport() {
            dialogState.value = SettingsDialog.Import()
        }

        private fun updateImport(transform: (ImportKeyDialogState) -> ImportKeyDialogState) {
            dialogState.update { (it as? SettingsDialog.Import)?.copy(form = transform(it.form)) ?: it }
        }

        /** A key file was picked: keep its text (never shown), name the key after the file if unnamed, pre-check it. */
        fun onImportFilePicked(file: PickedKeyFile) = updateImport { it.withFilePicked(file) }

        fun onImportSource(source: KeySource) = updateImport { it.withSource(source) }

        fun onImportChange(
            label: String? = null,
            pem: String? = null,
            passphrase: String? = null,
        ) = updateImport { it.withChange(label, pem, passphrase) }

        fun import() {
            val d = dialogState.value as? SettingsDialog.Import ?: return
            if (d.form.busy) return
            val problem = d.form.validate()
            if (problem != null) {
                dialogState.value = d.copy(form = d.form.copy(problem = problem))
                return
            }
            dialogState.value = d.copy(form = d.form.copy(busy = true, error = null, problem = null))
            viewModelScope.launch {
                when (val result = keyRepository.importFrom(d.form)) {
                    is AppResult.Success -> {
                        dialogState.value = null
                        eventChannel.trySend(
                            SettingsEvent.Info(UiText.Res(R.string.keys_imported, listOf(result.value.label))),
                        )
                    }

                    is AppResult.Failure -> {
                        updateImport { it.copy(busy = false, error = result.error.toUiError()) }
                    }
                }
            }
        }

        fun requestDelete(item: KeyItem) {
            dialogState.value = SettingsDialog.Delete(item)
        }

        fun confirmDelete() {
            val d = dialogState.value as? SettingsDialog.Delete ?: return
            dialogState.value = null
            viewModelScope.launch {
                when (val r = keyRepository.delete(d.item.key.alias)) {
                    is AppResult.Success -> eventChannel.trySend(SettingsEvent.Info(UiText.Res(R.string.keys_deleted)))
                    is AppResult.Failure -> eventChannel.trySend(SettingsEvent.Failure(r.error.toUiError()))
                }
            }
        }

        companion object {
            const val MIN_FONT_SP = MIN_TERMINAL_FONT_SP
            const val MAX_FONT_SP = MAX_TERMINAL_FONT_SP
        }
    }
