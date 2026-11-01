package com.mtajchert.flocket.feature.hosts.impl.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.component.ImportKeyDialogState
import com.mtajchert.flocket.core.ui.component.KeySource
import com.mtajchert.flocket.core.ui.component.PickedKeyFile
import com.mtajchert.flocket.core.ui.component.importFrom
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.hostform.AuthKind
import com.mtajchert.flocket.core.ui.hostform.HostFormError
import com.mtajchert.flocket.core.ui.hostform.HostFormField
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HostEditUiState(
    val isNew: Boolean = true,
    val loading: Boolean = true,
    val form: HostFormState = HostFormState(),
    /** Empty until the user tried to save once; then kept live while they fix fields. */
    val errors: Map<HostFormField, HostFormError> = emptyMap(),
    val keys: List<DeviceKey> = emptyList(),
    val saving: Boolean = false,
    val creatingKey: Boolean = false,
    /** The "Import a private key" dialog, or null when it is closed. */
    val importDialog: ImportKeyDialogState? = null,
    val error: UiError? = null,
    /** One-shot result delivered as state: the screen navigates away when true. */
    val saved: Boolean = false,
)

@HiltViewModel(assistedFactory = HostEditViewModel.Factory::class)
class HostEditViewModel
    @AssistedInject
    constructor(
        @Assisted private val hostId: String?,
        private val savedState: SavedStateHandle,
        private val hostRepository: HostRepository,
        private val deviceKeyRepository: DeviceKeyRepository,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(hostId: String?): HostEditViewModel
        }

        private val form = MutableStateFlow(savedState.get<HostFormState>(FORM_KEY) ?: HostFormState())
        private val transient =
            MutableStateFlow(
                HostEditUiState(
                    isNew = hostId == null,
                    loading =
                        hostId != null && !savedState.contains(FORM_KEY),
                ),
            )
        private val showErrors = MutableStateFlow(savedState.get<Boolean>(SHOW_ERRORS_KEY) ?: false)

        val uiState: StateFlow<HostEditUiState> =
            combine(form, transient, deviceKeyRepository.keys, showErrors) { form, t, keys, show ->
                t.copy(form = form, keys = keys, errors = if (show) form.validate() else emptyMap())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), transient.value)

        init {
            if (hostId != null && transient.value.loading) {
                viewModelScope.launch {
                    hostRepository.host(HostId(hostId))?.let { setForm(HostFormState.from(it)) }
                    transient.update { it.copy(loading = false) }
                }
            }
        }

        private fun setForm(value: HostFormState) {
            form.value = value
            savedState[FORM_KEY] = value
        }

        fun onFormChange(value: HostFormState) = setForm(value)

        fun createKey() {
            viewModelScope.launch {
                transient.update { it.copy(creatingKey = true, error = null) }
                when (val result = deviceKeyRepository.generate(DEFAULT_KEY_LABEL)) {
                    is AppResult.Success -> {
                        setForm(form.value.copy(keyAlias = result.value.alias))
                        transient.update { it.copy(creatingKey = false) }
                    }

                    is AppResult.Failure -> {
                        transient.update { it.copy(creatingKey = false, error = result.error.toUiError()) }
                    }
                }
            }
        }

        // ---- Import an existing key ---------------------------------------------------------------

        fun requestImport() = transient.update { it.copy(importDialog = ImportKeyDialogState(), error = null) }

        fun dismissImport() = transient.update { it.copy(importDialog = null) }

        private fun updateImport(transform: (ImportKeyDialogState) -> ImportKeyDialogState) =
            transient.update { t -> t.importDialog?.let { t.copy(importDialog = transform(it)) } ?: t }

        fun onImportFilePicked(file: PickedKeyFile) = updateImport { it.withFilePicked(file) }

        fun onImportFileFailed() = updateImport { it.withFileFailed() }

        fun onImportSource(source: KeySource) = updateImport { it.withSource(source) }

        fun onImportChange(
            label: String? = null,
            pem: String? = null,
            passphrase: String? = null,
        ) = updateImport { it.withChange(label, pem, passphrase) }

        /**
         * Stores the key, then switches the form to key sign-in with it selected. Nothing is generated. On failure
         * the dialog stays open with the error and every input kept.
         */
        fun importKey() {
            val dialog = transient.value.importDialog ?: return
            if (dialog.busy) return
            val problem = dialog.validate()
            if (problem != null) {
                updateImport { it.copy(problem = problem) }
                return
            }
            updateImport { it.copy(busy = true, error = null, problem = null) }
            viewModelScope.launch {
                when (val result = deviceKeyRepository.importFrom(dialog)) {
                    is AppResult.Success -> {
                        setForm(form.value.copy(authKind = AuthKind.Key, keyAlias = result.value.alias))
                        transient.update { it.copy(importDialog = null) }
                    }

                    is AppResult.Failure -> {
                        updateImport { it.copy(busy = false, error = result.error.toUiError()) }
                    }
                }
            }
        }

        fun save() {
            showErrors.value = true
            savedState[SHOW_ERRORS_KEY] = true
            val current = form.value
            if (current.validate().isNotEmpty()) return
            viewModelScope.launch {
                transient.update { it.copy(saving = true, error = null) }
                when (val result = hostRepository.save(current.toDraft())) {
                    is AppResult.Success -> {
                        transient.update { it.copy(saving = false, saved = true) }
                    }

                    is AppResult.Failure -> {
                        transient.update {
                            it.copy(
                                saving = false,
                                error = result.error.toUiError(),
                            )
                        }
                    }
                }
            }
        }

        private companion object {
            const val FORM_KEY = "form"
            const val SHOW_ERRORS_KEY = "showErrors"
            const val DEFAULT_KEY_LABEL = "flocket-device"
        }
    }
