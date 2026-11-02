package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.AgentCatalog
import com.mtajchert.flocket.core.model.AgentKind
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.WorkspaceSpace
import com.mtajchert.flocket.core.model.buildWorkspace
import com.mtajchert.flocket.core.model.folderName
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

private val LEADING_GLYPHS = Regex("^[^\\p{L}\\p{N}]+")
private val PATH_LIKE = Regex("^[~/]|^[A-Za-z]:\\\\")

/**
 * A terminal title worth showing, like the desktop's Agents panel: leading glyphs (an agent's spinner) are
 * dropped, and paths, `user@host` prompts and the program's own name say nothing. Null when nothing is left.
 */
internal fun tellingTitle(
    raw: String,
    agent: AgentKind?,
    agentLabel: String,
): String? {
    // The path check also looks at the raw title: dropping glyphs would turn `~/code` into `code`.
    if (PATH_LIKE.containsMatchIn(raw.trim())) return null
    val live = LEADING_GLYPHS.replaceFirst(raw, "").trim()
    return live.takeUnless {
        it.isEmpty() ||
            PATH_LIKE.containsMatchIn(it) ||
            '@' in it ||
            it == agent?.name ||
            it.equals(agent?.id, ignoreCase = true) ||
            it.equals(agentLabel, ignoreCase = true)
    }
}

/**
 * What a row says after its project, like the desktop: a tab someone named, else the program's own title,
 * else the terminal's name. Never an automatic tab number (see [buildSections] for the one exception).
 */
internal fun rowDetail(
    tabName: String,
    item: TerminalItem,
): String? = tabName.takeIf { it.isNotBlank() } ?: item.liveTitle ?: item.name?.takeIf { it.isNotBlank() }

internal fun TerminalRecord.toItem(writable: Boolean): TerminalItem {
    val finished = liveness as? Liveness.Finished
    val kind =
        when (liveness) {
            Liveness.Running -> LivenessKind.Running
            is Liveness.Waiting -> LivenessKind.Waiting
            is Liveness.Finished -> LivenessKind.Finished
        }
    val endpoint = ref.endpoint
    val agent = AgentCatalog.of(this)
    return TerminalItem(
        ref = ref,
        key = "${endpoint.hostId.value}/${endpoint.session.orEmpty()}/${address.value}",
        agentLabel = agentLabel,
        agentId = agent?.id,
        name = name,
        status = status,
        headline = statusLine() ?: title.ifBlank { where.orEmpty() },
        cwd = shortenPath(displayCwd),
        sinceMs = if (finished != null) finished.finishedAtMs ?: statusChangedAtMs else statusChangedAtMs,
        liveness = kind,
        exitCode = finished?.exitCode,
        signal = finished?.signal,
        urgency = urgencyOf(status, finished != null),
        writable = writable,
        liveTitle = tellingTitle(title, agent, agentLabel),
    )
}

/** What the program says about its current state (OSC 7501 `msg`, else `title`), if it said anything. */
private fun TerminalRecord.statusLine(): String? {
    val reported = status as? AgentStatus.Reported ?: return null
    return reported.message?.takeIf { it.isNotBlank() } ?: reported.title?.takeIf { it.isNotBlank() }
}

internal data class ListControls(
    val filter: TerminalFilter = TerminalFilter.All,
    val sort: TerminalSort = TerminalSort.Priority,
)

private fun TerminalItem.matches(controls: ListControls): Boolean =
    when (controls.filter) {
        TerminalFilter.All -> true
        TerminalFilter.NeedsAttention -> needsAttention()
        TerminalFilter.Working -> isWorking()
    }

/**
 * Arranges the already filtered [rows] per machine (the host's own first). In Priority mode each
 * machine gets the desktop's status sections with rows in core order; in Grouped mode it gets
 * space -> tab -> rows, all ordered by desktop rank (stable, so ties keep the layout order).
 */
internal fun buildSections(
    rows: List<TerminalItem>,
    workspace: Workspace,
    session: SessionState,
    controls: ListControls,
): List<MachineSection> {
    fun info(machine: String?) = machine?.let { name -> session.machines.firstOrNull { it.name == name } }
    val online = session.connection is ConnectionState.Online

    // A write goes out only to an online host and, for a federated space, to a connected machine.
    fun canRename(machine: String?) = online && (machine == null || info(machine)?.state == MachineState.Connected)
    val places = workspace.rowPlaces(::canRename)
    val shown = withDetails(rows, places)
    if (controls.sort != TerminalSort.Grouped) {
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
                        tab to
                            TabSection(
                                tab.key,
                                tab.name,
                                rows.sortedByDescending { it.urgency },
                                tab.number,
                                tab.layoutRef,
                                canRename(space.machine),
                            )
                    }
                }
            if (tabs.isEmpty()) {
                null
            } else {
                val ordered = tabs.sortedByDescending { it.first.topState.rank() }.map { it.second }
                space.machine to
                    SpaceSection(
                        space.key,
                        space.name,
                        subtitle,
                        space.topState,
                        ordered,
                        space.number,
                        space.layoutRef,
                        space.givenName,
                        space.autoName(),
                        canRename(space.machine),
                    )
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

/**
 * Sets each row's [TerminalItem.detail]. Rows of one space with nothing to tell them apart get their tab
 * numbers instead, as on the desktop.
 */
private fun withDetails(
    rows: List<TerminalItem>,
    places: Map<TerminalAddress, RowPlace>,
): List<TerminalItem> {
    val details = rows.map { rowDetail(places[it.ref.address]?.tabName.orEmpty(), it) }
    val blankPerSpace =
        rows
            .filterIndexed { i, _ -> details[i] == null }
            .groupingBy { places[it.ref.address]?.spaceKey }
            .eachCount()
    return rows.mapIndexed { i, item ->
        val place = places[item.ref.address]
        val detail =
            details[i] ?: place
                ?.takeIf { it.spaceNumber > 0 && (blankPerSpace[it.spaceKey] ?: 0) > 1 }
                ?.tabNumber
                ?.toString()
        if (detail == item.detail) item else item.copy(detail = detail)
    }
}

private fun AgentState?.rank(): Int = this?.priorityRank ?: -1

/** The desktop section a row belongs to. A reported but unrecognised state counts as no status. */
internal fun TerminalItem.bucket(): PriorityBucket =
    when ((status as? AgentStatus.Reported)?.state) {
        AgentState.Blocked, AgentState.Error -> PriorityBucket.NeedsYou
        AgentState.Done -> PriorityBucket.Done
        AgentState.Working -> PriorityBucket.Working
        AgentState.Idle -> PriorityBucket.Idle
        AgentState.Other, null -> PriorityBucket.NoStatus
    }

private fun Workspace.rowPlaces(canRename: (String?) -> Boolean): Map<TerminalAddress, RowPlace> {
    val places = HashMap<TerminalAddress, RowPlace>()
    for (space in spaces) {
        for (tab in space.tabs) {
            val place =
                RowPlace(
                    space.key,
                    space.name,
                    space.number,
                    tab.key,
                    tab.name,
                    tab.number,
                    space.tabs.size > 1,
                    space.layoutRef,
                    tab.layoutRef,
                    space.givenName,
                    space.autoName(),
                    canRename(space.machine),
                )
            for (pane in tab.panes) places.putIfAbsent(pane, place)
        }
    }
    return places
}

/** The name the desktop shows for an unnamed space: its shown folder's name (blank = "Space N"). */
private fun WorkspaceSpace.autoName(): String = folderName(folder).orEmpty()

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
    val groups =
        overview.mapNotNull { (host, session) ->
            val online = session.connection is ConnectionState.Online
            // Finished terminals are never listed (the desktop's Agents panel hides them too).
            val items =
                session.terminals.filter { it.liveness !is Liveness.Finished }.map { it.toItem(writable = online) }
            totalTerminals += items.size
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
        refreshing = refreshing,
        emptyKind = empty,
        canCreate = anyOnline,
    )
}

/** One row of the Priority list: a terminal plus where it lives and which host it is on. */
@Immutable
internal data class PriorityListRow(
    val row: PriorityRow,
    val hostId: HostId,
    val hostLabel: String,
)

/** One status section of the Priority list. */
@Immutable
internal data class PriorityListSection(
    val bucket: PriorityBucket,
    val rows: List<PriorityListRow>,
)

/** The Priority list: the hosts' status sections flattened into one list across hosts, optionally for a single host. */
internal fun prioritySections(
    groups: List<HostGroup>,
    hostFilter: HostId? = null,
): List<PriorityListSection> {
    val rows =
        groups
            .filter { hostFilter == null || it.hostId == hostFilter }
            .flatMap { group ->
                group.sections.flatMap { section ->
                    section.priority.flatMap { prio ->
                        prio.rows.map { prio.bucket to PriorityListRow(it, group.hostId, group.label) }
                    }
                }
            }
    // PriorityBucket declares the desktop order: Needs you, Done, Working, Idle, no status.
    return PriorityBucket.entries.mapNotNull { bucket ->
        rows.filter { it.first == bucket }.map { it.second }.takeIf { it.isNotEmpty() }?.let {
            PriorityListSection(
                bucket,
                it,
            )
        }
    }
}

/** Needs-you terminals on one host. */
internal fun HostGroup.needsYouCount(): Int = sections.sumOf { s -> s.items.count { it.needsAttention() } }
