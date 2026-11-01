package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.buildWorkspace
import com.mtajchert.flocket.core.session.SessionState
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
    val sort: TerminalSort = TerminalSort.Priority,
    val showFinished: Boolean = false,
)

private fun TerminalItem.matches(controls: ListControls): Boolean =
    when (controls.filter) {
        TerminalFilter.All -> controls.showFinished || !isFinished
        TerminalFilter.NeedsAttention -> needsAttention()
        TerminalFilter.Working -> isWorking()
        TerminalFilter.Finished -> isFinished
    }

/**
 * Arranges the already filtered [shown] rows per machine (the host's own first). In Priority mode each
 * machine gets the desktop's status sections with rows in core order; in Grouped mode it gets
 * space -> tab -> rows, all ordered by desktop rank (stable, so ties keep the layout order).
 */
internal fun buildSections(
    shown: List<TerminalItem>,
    workspace: Workspace,
    session: SessionState,
    controls: ListControls,
): List<MachineSection> {
    fun info(machine: String?) = machine?.let { name -> session.machines.firstOrNull { it.name == name } }
    if (controls.sort == TerminalSort.Priority) {
        val places = workspace.rowPlaces()
        return shown
            .groupBy { it.ref.address.machine }
            .entries
            .sortedBy { if (it.key == null) 0 else 1 }
            .map { (machine, items) ->
                val rows = items.map { PriorityRow(it, places[it.ref.address] ?: RowPlace.None) }
                val sections =
                    PriorityBucket.entries.mapNotNull { bucket ->
                        rows.filter { it.item.bucket() == bucket }.takeIf { it.isNotEmpty() }?.let {
                            PrioritySection(bucket, it)
                        }
                    }
                val i = info(machine)
                MachineSection(machine, i?.state, i?.rawState, spaces = emptyList(), priority = sections)
            }
    }
    val byAddress = shown.associateBy { it.ref.address }
    val spaces =
        workspace.spaces.mapNotNull { space ->
            val subtitle =
                space.folder?.let(::shortenPath)?.takeIf { it.isNotBlank() && !it.endsWithSegment(space.name) }
            val tabs =
                space.tabs.mapNotNull { tab ->
                    // A row's folder is redundant when the space header already shows it.
                    val rows =
                        tab.panes.mapNotNull { byAddress[it] }.map {
                            if (subtitle != null &&
                                it.cwd == subtitle
                            ) {
                                it.copy(cwd = "")
                            } else {
                                it
                            }
                        }
                    if (rows.isEmpty()) {
                        null
                    } else {
                        tab to TabSection(tab.key, tab.name, rows.sortedByDescending { it.urgency }, tab.number)
                    }
                }
            if (tabs.isEmpty()) {
                null
            } else {
                val ordered = tabs.sortedByDescending { it.first.topState.rank() }.map { it.second }
                space.machine to SpaceSection(space.key, space.name, subtitle, space.topState, ordered, space.number)
            }
        }
    val ordered = spaces.sortedByDescending { it.second.topState.rank() }
    return ordered
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { if (it.key == null) 0 else 1 }
        .map { (machine, list) ->
            val i = info(machine)
            MachineSection(machine, i?.state, i?.rawState, list)
        }
}

private fun AgentState?.rank(): Int = this?.priorityRank ?: -1

/** The desktop section a row belongs to. A reported but unrecognised state counts as no status. */
internal fun TerminalItem.bucket(): PriorityBucket {
    if (isFinished) return PriorityBucket.Finished
    return when ((status as? AgentStatus.Reported)?.state) {
        AgentState.Blocked, AgentState.Error -> PriorityBucket.NeedsYou
        AgentState.Done -> PriorityBucket.Done
        AgentState.Working -> PriorityBucket.Working
        AgentState.Idle -> PriorityBucket.Idle
        AgentState.Other, null -> PriorityBucket.NoStatus
    }
}

private fun Workspace.rowPlaces(): Map<TerminalAddress, RowPlace> {
    val places = HashMap<TerminalAddress, RowPlace>()
    for (space in spaces) {
        for (tab in space.tabs) {
            val place =
                RowPlace(space.key, space.name, space.number, tab.key, tab.name, tab.number, space.tabs.size > 1)
            for (pane in tab.panes) places.putIfAbsent(pane, place)
        }
    }
    return places
}

private fun String.endsWithSegment(name: String): Boolean = trimEnd('/').substringAfterLast('/') == name

/** Groups by host, then by federated machine (own terminals first), filtering and sorting each section. */
internal fun buildUiState(
    overview: List<HostOverview>,
    controls: ListControls,
    refreshing: Boolean,
    /** How a host's terminals are grouped; tests and previews inject fixed workspaces. */
    workspaceOf: (SessionState) -> Workspace = { buildWorkspace(it.terminals, it.desktopLayout, it.machineLayouts) },
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
                buildSections(shown, workspaceOf(session), session, controls)
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
        sort = controls.sort,
        showFinished = controls.showFinished,
        refreshing = refreshing,
        emptyKind = empty,
        canCreate = anyOnline,
        hiddenFinished = hiddenFinished,
    )
}
