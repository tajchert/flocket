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
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiText
import com.mtajchert.flocket.core.ui.error.toUiError
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

    data class Import(
        val label: String = "",
        val pem: String = "",
        val passphrase: String = "",
        val error: UiError? = null,
        val problem: ImportProblem? = null,
        val busy: Boolean = false,
    ) : SettingsDialog

    data class Delete(
        val item: KeyItem,
    ) : SettingsDialog

    companion object {
        const val DEFAULT_KEY_LABEL = "flocket"
    }
}

enum class ImportProblem { LabelMissing, NotAPrivateKey }

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
            update { it.copy(terminalFontSize = size.coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }

        fun setShowExtraKeys(enabled: Boolean) = update { it.copy(showExtraKeys = enabled) }

        fun setKeepScreenOn(enabled: Boolean) = update { it.copy(keepScreenOnInTerminal = enabled) }

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
                            SettingsEvent.Info(UiText.Res(R.string.keys_generated, listOf(r.value.alias))),
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

        fun requestImport(pem: String = "") {
            dialogState.value = SettingsDialog.Import(pem = pem)
        }

        /** A key file was picked: fill the text box (creating the dialog if needed). */
        fun onImportFileRead(pem: String) {
            dialogState.update {
                (it as? SettingsDialog.Import)?.copy(pem = pem, problem = null, error = null)
                    ?: SettingsDialog.Import(pem = pem)
            }
        }

        fun onImportChange(
            label: String? = null,
            pem: String? = null,
            passphrase: String? = null,
        ) {
            dialogState.update {
                (it as? SettingsDialog.Import)?.copy(
                    label = label ?: it.label,
                    pem = pem ?: it.pem,
                    passphrase = passphrase ?: it.passphrase,
                    problem = null,
                    error = null,
                ) ?: it
            }
        }

        fun import() {
            val d = dialogState.value as? SettingsDialog.Import ?: return
            if (d.busy) return
            val problem =
                when {
                    d.label.isBlank() -> ImportProblem.LabelMissing
                    !d.pem.contains("PRIVATE KEY") -> ImportProblem.NotAPrivateKey
                    else -> null
                }
            if (problem != null) {
                dialogState.value = d.copy(problem = problem)
                return
            }
            dialogState.value = d.copy(busy = true, error = null, problem = null)
            val pem = d.pem.trim().toCharArray()
            val passphrase = d.passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
            viewModelScope.launch {
                val result = keyRepository.import(d.label.trim(), pem, passphrase)
                pem.fill('\u0000')
                passphrase?.fill('\u0000')
                when (result) {
                    is AppResult.Success -> {
                        dialogState.value = null
                        eventChannel.trySend(
                            SettingsEvent.Info(UiText.Res(R.string.keys_imported, listOf(result.value.alias))),
                        )
                    }

                    is AppResult.Failure -> {
                        dialogState.update {
                            (it as? SettingsDialog.Import)?.copy(busy = false, error = result.error.toUiError())
                                ?: it
                        }
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
            const val MIN_FONT_SP = 8f
            const val MAX_FONT_SP = 24f
        }
    }
