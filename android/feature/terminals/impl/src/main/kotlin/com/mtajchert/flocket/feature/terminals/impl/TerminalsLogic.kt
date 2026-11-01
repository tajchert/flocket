package com.mtajchert.flocket.feature.terminals.impl

import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalRecord
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

/**
 * Arranges the already filtered [shown] rows as machine -> space -> tab. The host's own machine comes
 * first. With "attention first", spaces sort by their status urgency and rows by theirs (both stable).
 */
internal fun buildSections(
    shown: List<TerminalItem>,
    workspace: Workspace,
    session: SessionState,
    controls: ListControls,
): List<MachineSection> {
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
                        TabSection(
                            tab.key,
                            tab.name,
                            if (controls.attentionFirst) rows.sortedByDescending { it.urgency } else rows,
                        )
                    }
                }
            if (tabs.isEmpty()) {
                null
            } else {
                space.machine to SpaceSection(space.key, space.name, subtitle, space.topState, tabs)
            }
        }
    val ordered =
        if (controls.attentionFirst) spaces.sortedByDescending { it.second.topState?.urgency ?: -1 } else spaces
    return ordered
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { if (it.key == null) 0 else 1 }
        .map { (machine, list) ->
            val info = machine?.let { name -> session.machines.firstOrNull { it.name == name } }
            MachineSection(machine, info?.state, info?.rawState, list)
        }
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
        attentionFirst = controls.attentionFirst,
        showFinished = controls.showFinished,
        refreshing = refreshing,
        emptyKind = empty,
        canCreate = anyOnline,
        hiddenFinished = hiddenFinished,
    )
}
