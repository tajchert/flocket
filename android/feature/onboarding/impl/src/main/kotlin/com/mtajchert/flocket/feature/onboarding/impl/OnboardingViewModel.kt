@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.onboarding.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.data.PreferencesRepository
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.hostform.AuthKind
import com.mtajchert.flocket.core.ui.hostform.HostFormError
import com.mtajchert.flocket.core.ui.hostform.HostFormField
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import com.mtajchert.flocket.core.ui.trust.forgetAndRetry
import com.mtajchert.flocket.core.ui.trust.pinAndRetry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { Welcome, Prerequisites, DeviceKey, AddHost, Connect }

/** What the Connect step shows. [trustPrompt]/[changedPrompt]/[passwordPrompt] are dialogs requested by state. */
data class ConnectUiState(
    val hostLabel: String = "",
    val connection: ConnectionState = ConnectionState.Idle,
    val coreName: String? = null,
    val error: UiError? = null,
    val trustPrompt: AppError.HostKeyUnknown? = null,
    val changedPrompt: AppError.HostKeyChanged? = null,
    val passwordPrompt: Boolean = false,
    val connected: Boolean = false,
)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val keys: List<DeviceKey> = emptyList(),
    val form: HostFormState = HostFormState(),
    val formErrors: Map<HostFormField, HostFormError> = emptyMap(),
    val generatingKey: Boolean = false,
    val importOpen: Boolean = false,
    val importPem: String = "",
    val importPassphrase: String = "",
    val saving: Boolean = false,
    val error: UiError? = null,
    val connect: ConnectUiState = ConnectUiState(),
    /** Onboarding is complete: the route navigates to the app. */
    val finished: Boolean = false,
) {
    val selectedKey: DeviceKey? get() = keys.firstOrNull { it.alias == form.keyAlias }
    val usesPassword: Boolean get() = form.authKind == AuthKind.Password
    val canGoBack: Boolean get() = step != OnboardingStep.Welcome && !connect.connected
    val stepIndex: Int get() = step.ordinal
    val stepCount: Int get() = OnboardingStep.entries.size
}

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val deviceKeys: DeviceKeyRepository,
        private val hosts: HostRepository,
        private val trust: HostTrustRepository,
        private val connections: ConnectionManager,
        private val preferences: PreferencesRepository,
    ) : ViewModel() {
        private val step = MutableStateFlow(OnboardingStep.entries[savedState.get<Int>(STEP) ?: 0])
        private val form = MutableStateFlow(savedState.get<HostFormState>(FORM) ?: HostFormState())
        private val hostId = MutableStateFlow(savedState.get<String>(HOST_ID))
        private val local = MutableStateFlow(Local())

        private data class Local(
            val generating: Boolean = false,
            val importOpen: Boolean = false,
            val importPem: String = "",
            val importPassphrase: String = "",
            val saving: Boolean = false,
            val showFormErrors: Boolean = false,
            val error: UiError? = null,
            val dismissed: Set<String> = emptySet(),
            val finished: Boolean = false,
        )

        private val connectState =
            combine(connections.overview, hostId, local.map { it.dismissed }) { overview, id, dismissed ->
                val entry = id?.let { hid -> overview.firstOrNull { it.host.id.value == hid } }
                entry?.let { connectStateOf(it.host, it.session.connection, it.session.core?.name, dismissed) }
                    ?: ConnectUiState()
            }

        val uiState: StateFlow<OnboardingUiState> =
            combine(step, form, local, deviceKeys.keys, connectState) { step, form, local, keys, connect ->
                OnboardingUiState(
                    step = step,
                    keys = keys,
                    form = form,
                    formErrors = if (local.showFormErrors) form.validate() else emptyMap(),
                    generatingKey = local.generating,
                    importOpen = local.importOpen,
                    importPem = local.importPem,
                    importPassphrase = local.importPassphrase,
                    saving = local.saving,
                    error = local.error,
                    connect = connect,
                    finished = local.finished,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                OnboardingUiState(step = step.value, form = form.value),
            )

        init {
            viewModelScope.launch {
                connectState.first { it.connected }
                // Let the success state be seen before leaving.
                delay(SUCCESS_DWELL_MS)
                complete()
            }
        }

        // ---- Navigation between steps -------------------------------------------------------------

        private fun go(target: OnboardingStep) {
            step.value = target
            savedState[STEP] = target.ordinal
        }

        fun next() {
            when (step.value) {
                OnboardingStep.Welcome -> {
                    go(OnboardingStep.Prerequisites)
                }

                OnboardingStep.Prerequisites -> {
                    go(OnboardingStep.DeviceKey)
                    preselectExistingKey()
                }

                OnboardingStep.DeviceKey -> {
                    if (form.value.authKind == AuthKind.Password ||
                        form.value.keyAlias != null
                    ) {
                        go(OnboardingStep.AddHost)
                    }
                }

                OnboardingStep.AddHost -> {
                    saveHost()
                }

                OnboardingStep.Connect -> {
                    Unit
                }
            }
        }

        fun back() {
            val target = OnboardingStep.entries.getOrNull(step.value.ordinal - 1) ?: return
            go(target)
        }

        private fun preselectExistingKey() {
            if (form.value.authKind != AuthKind.Key || form.value.keyAlias != null) return
            viewModelScope.launch {
                deviceKeys.keys.first().lastOrNull()?.let {
                    setForm(
                        form.value.copy(keyAlias = it.alias),
                    )
                }
            }
        }

        // ---- Device key ---------------------------------------------------------------------------

        fun generateKey() {
            viewModelScope.launch {
                local.update { it.copy(generating = true, error = null) }
                when (val result = deviceKeys.generate(KEY_LABEL)) {
                    is AppResult.Success -> {
                        setForm(form.value.copy(authKind = AuthKind.Key, keyAlias = result.value.alias))
                        local.update { it.copy(generating = false) }
                    }

                    is AppResult.Failure -> {
                        local.update { it.copy(generating = false, error = result.error.toUiError()) }
                    }
                }
            }
        }

        fun toggleImport() = local.update { it.copy(importOpen = !it.importOpen, error = null) }

        fun onImportPem(value: String) = local.update { it.copy(importPem = value) }

        fun onImportPassphrase(value: String) = local.update { it.copy(importPassphrase = value) }

        fun importKey() {
            val current = local.value
            if (current.importPem.isBlank()) return
            viewModelScope.launch {
                local.update { it.copy(generating = true, error = null) }
                val result =
                    deviceKeys
                        .import(
                            KEY_LABEL,
                            current.importPem.toCharArray(),
                            current.importPassphrase
                                .takeIf {
                                    it.isNotEmpty()
                                }?.toCharArray(),
                        )
                when (result) {
                    is AppResult.Success -> {
                        setForm(form.value.copy(authKind = AuthKind.Key, keyAlias = result.value.alias))
                        local.update {
                            it.copy(
                                generating = false,
                                importOpen = false,
                                importPem = "",
                                importPassphrase = "",
                            )
                        }
                    }

                    is AppResult.Failure -> {
                        local.update { it.copy(generating = false, error = result.error.toUiError()) }
                    }
                }
            }
        }

        fun usePassword() {
            setForm(form.value.copy(authKind = AuthKind.Password, keyAlias = null))
            go(OnboardingStep.AddHost)
        }

        // ---- Host form ----------------------------------------------------------------------------

        private fun setForm(value: HostFormState) {
            form.value = value
            savedState[FORM] = value
        }

        fun onFormChange(value: HostFormState) = setForm(value)

        fun createKeyFromForm() = generateKey()

        private fun saveHost() {
            local.update { it.copy(showFormErrors = true) }
            val current = form.value.copy(id = hostId.value)
            if (current.validate().isNotEmpty()) return
            viewModelScope.launch {
                local.update { it.copy(saving = true, error = null) }
                when (val result = hosts.save(current.toDraft())) {
                    is AppResult.Success -> {
                        hostId.value = result.value.id.value
                        savedState[HOST_ID] = result.value.id.value
                        setForm(current.copy(id = result.value.id.value))
                        local.update { it.copy(saving = false, dismissed = emptySet()) }
                        go(OnboardingStep.Connect)
                    }

                    is AppResult.Failure -> {
                        local.update { it.copy(saving = false, error = result.error.toUiError()) }
                    }
                }
            }
        }

        // ---- Connect ------------------------------------------------------------------------------

        private fun connectStateOf(
            host: Host,
            connection: ConnectionState,
            coreName: String?,
            dismissed: Set<String>,
        ): ConnectUiState {
            val error =
                (connection as? ConnectionState.NeedsAttention)?.error
                    ?: (connection as? ConnectionState.Reconnecting)?.lastError
            val unknown = error as? AppError.HostKeyUnknown
            val changed = error as? AppError.HostKeyChanged
            val passwordAuthFailed = error is AppError.AuthFailed && host.auth is HostAuth.Password
            return ConnectUiState(
                hostLabel = host.label,
                connection = connection,
                coreName = coreName,
                error = error?.toUiError(),
                trustPrompt = unknown?.takeIf { "trust:${it.fingerprint}" !in dismissed },
                changedPrompt = changed?.takeIf { "changed" !in dismissed },
                passwordPrompt = passwordAuthFailed && "password" !in dismissed,
                connected = connection is ConnectionState.Online,
            )
        }

        private fun currentHost(): HostId? = hostId.value?.let(::HostId)

        fun trustHostKey() {
            val prompt = uiState.value.connect.trustPrompt ?: return
            val id = currentHost() ?: return
            viewModelScope.launch {
                val host = hosts.host(id) ?: return@launch
                trust.pinAndRetry(host, prompt, connections.session(id))
            }
        }

        fun dismissTrust() {
            val prompt = uiState.value.connect.trustPrompt ?: return
            local.update { it.copy(dismissed = it.dismissed + "trust:${prompt.fingerprint}") }
        }

        fun dismissChanged() = local.update { it.copy(dismissed = it.dismissed + "changed") }

        fun forgetPinnedKey() {
            val id = currentHost() ?: return
            viewModelScope.launch {
                val host = hosts.host(id) ?: return@launch
                trust.forgetAndRetry(host, connections.session(id))
                local.update { it.copy(dismissed = emptySet()) }
            }
        }

        fun submitPassword(password: CharArray) {
            val id = currentHost() ?: return
            local.update { it.copy(dismissed = it.dismissed + "password") }
            connections.providePassword(id, password)
        }

        fun dismissPassword() = local.update { it.copy(dismissed = it.dismissed + "password") }

        /** Handle an action chip on the connect error. Returns true for [UiErrorAction.InstallHelp] (screen opens the URL). */
        fun onErrorAction(action: UiErrorAction): Boolean {
            when (action) {
                UiErrorAction.Retry -> {
                    retry()
                }

                UiErrorAction.TrustHostKey -> {
                    local.update {
                        it.copy(
                            dismissed =
                                it.dismissed
                                    .filterNot { d ->
                                        d.startsWith("trust:")
                                    }.toSet(),
                        )
                    }
                }

                UiErrorAction.ForgetPinnedKey -> {
                    local.update { it.copy(dismissed = it.dismissed - "changed") }
                }

                UiErrorAction.ReenterPassword -> {
                    local.update { it.copy(dismissed = it.dismissed - "password") }
                }

                UiErrorAction.EditHost -> {
                    go(OnboardingStep.AddHost)
                }

                UiErrorAction.InstallHelp -> {
                    return true
                }
            }
            return false
        }

        fun retry() {
            currentHost()?.let { connections.session(it)?.retryNow() }
        }

        fun editHost() = go(OnboardingStep.AddHost)

        /** Leave without a verified connection: the host stays, onboarding is done. */
        fun skip() {
            viewModelScope.launch { complete() }
        }

        private suspend fun complete() {
            preferences.update { it.copy(onboardingCompleted = true) }
            local.update { it.copy(finished = true) }
        }

        private companion object {
            const val STEP = "step"
            const val FORM = "form"
            const val HOST_ID = "hostId"
            const val KEY_LABEL = "flocket-device"
            const val SUCCESS_DWELL_MS = 1_200L
        }
    }
