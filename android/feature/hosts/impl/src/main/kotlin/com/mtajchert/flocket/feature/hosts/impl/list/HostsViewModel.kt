package com.mtajchert.flocket.feature.hosts.impl.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.data.HostSettingsRepository
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.core.ui.error.toUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Terminals of one host by how much attention they need. Finished terminals only count in [total]. */
data class TerminalCounts(
    val blocked: Int,
    val working: Int,
    val total: Int,
)

data class HostItem(
    val id: HostId,
    val label: String,
    val address: UserAtHost,
    val connection: ConnectionState,
    val coreName: String?,
    val coreVersion: String?,
    val counts: TerminalCounts,
    /** The host overrides some app-level terminal defaults. */
    val customized: Boolean = false,
    /** Why the host needs attention (error / needs setup); shown as an inline note on the card. */
    val error: UiError? = null,
    /** Federated machines (`machine.list`), empty when none. */
    val machines: List<MachineInfo> = emptyList(),
)

data class UserAtHost(
    val user: String,
    val host: String,
    val port: Int,
)

data class HostsUiState(
    val hosts: List<HostItem> = emptyList(),
)

@HiltViewModel
class HostsViewModel
    @Inject
    constructor(
        connectionManager: ConnectionManager,
        hostSettings: HostSettingsRepository,
    ) : ViewModel() {
        val uiState: StateFlow<HostsUiState> =
            combine(connectionManager.overview, hostSettings.customizedHosts) { overview, custom ->
                HostsUiState(overview.map { toItem(it, it.host.id in custom) })
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                HostsUiState(connectionManager.overview.value.map { toItem(it) }),
            )
    }

internal fun toItem(
    overview: HostOverview,
    customized: Boolean = false,
): HostItem {
    val host = overview.host
    val running = overview.session.terminals.filter { it.liveness !is Liveness.Finished }

    fun count(vararg states: AgentState) = running.count { (it.status as? AgentStatus.Reported)?.state in states }
    return HostItem(
        id = host.id,
        label = host.label,
        address = UserAtHost(host.username, host.hostname, host.port),
        connection = overview.session.connection,
        coreName = overview.session.core?.name,
        coreVersion = overview.session.core?.version,
        counts =
            TerminalCounts(
                count(AgentState.Blocked, AgentState.Error),
                count(AgentState.Working),
                overview.session.terminals.size,
            ),
        customized = customized,
        error =
            when (val c = overview.session.connection) {
                is ConnectionState.NeedsAttention -> c.error.toUiError()
                is ConnectionState.Reconnecting -> c.lastError?.toUiError()
                else -> null
            },
        machines = overview.session.machines,
    )
}
