package com.mtajchert.flocket.feature.hosts.impl.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.PinnedHostKey
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.trust.forgetAndRetry
import com.mtajchert.flocket.core.ui.trust.pinAndRetry
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The single dialog the detail screen may show. */
sealed interface HostDetailDialog {
    data class TrustKey(
        val error: AppError.HostKeyUnknown,
    ) : HostDetailDialog

    data class KeyChanged(
        val error: AppError.HostKeyChanged,
    ) : HostDetailDialog

    data object ForgetPinned : HostDetailDialog

    data object Delete : HostDetailDialog

    data object Password : HostDetailDialog
}

data class HostDetailUiState(
    val loading: Boolean = true,
    val host: Host? = null,
    val connection: ConnectionState = ConnectionState.Idle,
    val error: UiError? = null,
    val core: CoreInfo? = null,
    val machines: List<MachineInfo> = emptyList(),
    val terminalCount: Int = 0,
    val confirmedAtMs: Long? = null,
    val pinnedKey: PinnedHostKey? = null,
    val dialog: HostDetailDialog? = null,
    /** Set after a successful delete; the screen navigates away. */
    val deleted: Boolean = false,
) {
    val notFound: Boolean get() = !loading && host == null && !deleted
}

/** Where the screen should go for actions the ViewModel can't perform itself. */
enum class HostDetailNavigation { Edit, InstallHelp }

@HiltViewModel(assistedFactory = HostDetailViewModel.Factory::class)
class HostDetailViewModel
    @AssistedInject
    constructor(
        @Assisted hostId: String,
        private val connectionManager: ConnectionManager,
        private val hostRepository: HostRepository,
        private val trustRepository: HostTrustRepository,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(hostId: String): HostDetailViewModel
        }

        private val id = HostId(hostId)
        private val dialog = MutableStateFlow<HostDetailDialog?>(null)
        private val deleted = MutableStateFlow(false)
        private val pinRefresh = MutableStateFlow(0)

        val uiState: StateFlow<HostDetailUiState> =
            combine(connectionManager.overview, dialog, deleted, pinRefresh) { overview, dialog, deleted, _ ->
                val entry = overview.firstOrNull { it.host.id == id }
                if (entry == null) {
                    HostDetailUiState(loading = false, deleted = deleted, dialog = dialog)
                } else {
                    val session = entry.session
                    val error =
                        when (val c = session.connection) {
                            is ConnectionState.NeedsAttention -> c.error
                            is ConnectionState.Reconnecting -> c.lastError
                            else -> null
                        }
                    HostDetailUiState(
                        loading = false,
                        host = entry.host,
                        connection = session.connection,
                        error = error?.toUiError(),
                        core = session.core,
                        machines = session.machines,
                        terminalCount = session.terminals.size,
                        confirmedAtMs = session.confirmedAtMs,
                        pinnedKey = trustRepository.pinned(entry.host.hostname, entry.host.port),
                        dialog = dialog,
                        deleted = deleted,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostDetailUiState())

        private val current get() = uiState.value

        /** Handles an error action; returns a navigation request for the ones that leave this screen. */
        fun onErrorAction(action: UiErrorAction): HostDetailNavigation? {
            val source = current.error?.source
            when (action) {
                UiErrorAction.Retry -> {
                    retry()
                }

                UiErrorAction.TrustHostKey -> {
                    (source as? AppError.HostKeyUnknown)?.let {
                        dialog.value =
                            HostDetailDialog.TrustKey(it)
                    }
                }

                UiErrorAction.ForgetPinnedKey -> {
                    (source as? AppError.HostKeyChanged)?.let {
                        dialog.value =
                            HostDetailDialog.KeyChanged(it)
                    }
                }

                UiErrorAction.ReenterPassword -> {
                    dialog.value = HostDetailDialog.Password
                }

                UiErrorAction.EditHost -> {
                    return HostDetailNavigation.Edit
                }

                UiErrorAction.InstallHelp -> {
                    return HostDetailNavigation.InstallHelp
                }
            }
            return null
        }

        fun retry() {
            connectionManager.session(id)?.retryNow()
        }

        fun requestForgetPinned() {
            dialog.value = HostDetailDialog.ForgetPinned
        }

        fun requestDelete() {
            dialog.value = HostDetailDialog.Delete
        }

        fun dismissDialog() {
            dialog.value = null
        }

        fun trust() {
            val host = current.host ?: return
            val error = (dialog.value as? HostDetailDialog.TrustKey)?.error ?: return
            dialog.value = null
            viewModelScope.launch {
                trustRepository.pinAndRetry(host, error, connectionManager.session(id))
                pinRefresh.update { it + 1 }
            }
        }

        fun forgetPinned() {
            val host = current.host ?: return
            dialog.value = null
            viewModelScope.launch {
                trustRepository.forgetAndRetry(host, connectionManager.session(id))
                pinRefresh.update { it + 1 }
            }
        }

        fun submitPassword(password: CharArray) {
            dialog.value = null
            connectionManager.providePassword(id, password)
        }

        fun delete() {
            dialog.value = null
            viewModelScope.launch {
                if (hostRepository.delete(id) is AppResult.Success) deleted.value = true
            }
        }
    }
