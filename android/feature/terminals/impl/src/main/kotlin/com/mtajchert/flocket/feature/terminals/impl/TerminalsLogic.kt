package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.ui.error.toUiError

private val HOME = Regex("^(/Users/[^/]+|/home/[^/]+|/root)(?=/|$)")
private const val MAX_PATH = 34

/** `/Users/me/code/flocket/app` -> `~/code/flocket/app`; very long paths keep the first and last two segments. */
internal fun shortenPath(path: String): String {
    if (path.isBlank()) return ""
    val p = HOME.replaceFirst(path, "~")
    if (p.length <= MAX_PATH) return p
    val parts = p.split('/')
    if (parts.size <= 4) return p
    val head = parts.first().ifEmpty { "" }
    return "$head/…/${parts[parts.size - 2]}/${parts.last()}"
}

internal fun TerminalRecord.toItem(writable: Boolean): TerminalItem {
    val finished = liveness as? Liveness.Finished
    val kind =
        when (liveness) {
            Liveness.Running -> LivenessKind.Running
            is Liveness.Waiting -> LivenessKind.Waiting
            is Liveness.Finished -> LivenessKind.Finished
        }
    val endpoint = ref.endpoint
    return TerminalItem(
        ref = ref,
        key = "${endpoint.hostId.value}/${endpoint.session.orEmpty()}/${address.value}",
        agentLabel = agentLabel,
        name = name,
        status = status,
        headline = title.ifBlank { where.orEmpty() },
        cwd = shortenPath(displayCwd),
        sinceMs = if (finished != null) finished.finishedAtMs ?: statusChangedAtMs else statusChangedAtMs,
        liveness = kind,
        exitCode = finished?.exitCode,
        signal = finished?.signal,
        urgency = urgencyOf(status, finished != null),
        writable = writable,
    )
}

internal data class ListControls(
    val filter: TerminalFilter = TerminalFilter.All,
    val attentionFirst: Boolean = true,
    val showFinished: Boolean = false,
)

private fun TerminalItem.matches(controls: ListControls): Boolean =
    when (controls.filter) {
        TerminalFilter.All -> controls.showFinished || !isFinished
        TerminalFilter.NeedsAttention -> needsAttention()
        TerminalFilter.Working -> isWorking()
        TerminalFilter.Finished -> isFinished
    }

/** Groups by host, then by federated machine (own terminals first), filtering and sorting each section. */
internal fun buildUiState(
    overview: List<HostOverview>,
    controls: ListControls,
    refreshing: Boolean,
): TerminalsUiState {
    var totalTerminals = 0
    var hiddenFinished = 0
    val groups =
        overview.mapNotNull { (host, session) ->
            val online = session.connection is ConnectionState.Online
            val items = session.terminals.map { it.toItem(writable = online) }
            totalTerminals += items.size
            if (controls.filter == TerminalFilter.All &&
                !controls.showFinished
            ) {
                hiddenFinished += items.count { it.isFinished }
            }
            val shown = items.filter { it.matches(controls) }
            val sections =
                shown
                    .groupBy { it.ref.address.machine }
                    .entries
                    .sortedBy { if (it.key == null) 0 else 1 }
                    .map { (machine, list) ->
                        val info = machine?.let { name -> session.machines.firstOrNull { it.name == name } }
                        MachineSection(
                            machine = machine,
                            machineState = info?.state,
                            machineStateRaw = info?.rawState,
                            items = if (controls.attentionFirst) list.sortedByDescending { it.urgency } else list,
                        )
                    }
            // A filter hides hosts without matches, except hosts that need the user's eye.
            if (shown.isEmpty() && online && controls.filter != TerminalFilter.All) return@mapNotNull null
            HostGroup(
                hostId = host.id,
                label = host.label,
                connection = session.connection,
                confirmedAtMs = session.confirmedAtMs,
                error = (session.connection as? ConnectionState.NeedsAttention)?.error?.toUiError(),
                sections = sections,
                total = items.size,
            )
        }
    val anyOnline = overview.any { it.session.connection is ConnectionState.Online }
    val visible = groups.sumOf { it.visibleCount }
    val empty =
        when {
            overview.isEmpty() -> EmptyKind.NoHosts

            totalTerminals == 0 && !anyOnline -> EmptyKind.AllOffline

            totalTerminals == 0 &&
                overview.all {
                    it.session.connection is ConnectionState.Online
                }
            -> EmptyKind.NoTerminals

            totalTerminals == 0 -> null

            visible == 0 && groups.all { it.online } -> EmptyKind.NoMatches

            else -> null
        }
    return TerminalsUiState(
        loaded = true,
        groups = groups,
        filter = controls.filter,
        attentionFirst = controls.attentionFirst,
        showFinished = controls.showFinished,
        refreshing = refreshing,
        emptyKind = empty,
        canCreate = anyOnline,
        hiddenFinished = hiddenFinished,
    )
}
