package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.model.AgentCatalog
import com.mtajchert.flocket.core.model.AgentKind
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.model.HerdrLabels
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.model.ShownStatus
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.WorkspaceSpace
import com.mtajchert.flocket.core.model.buildWorkspace
import com.mtajchert.flocket.core.model.childShownStatus
import com.mtajchert.flocket.core.model.folderName
import com.mtajchert.flocket.core.model.isAgent
import com.mtajchert.flocket.core.model.isPinned
import com.mtajchert.flocket.core.model.shownStatus
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

internal fun TerminalRecord.toItem(
    writable: Boolean,
    supportsLabels: Boolean = false,
    /** The hold rule keeps a Done -> Idle terminal at Done rank (see [HoldRule]). */
    held: Boolean = false,
): TerminalItem {
    val finished = liveness as? Liveness.Finished
    val kind =
        when (liveness) {
            Liveness.Running -> LivenessKind.Running
            is Liveness.Waiting -> LivenessKind.Waiting
            is Liveness.Finished -> LivenessKind.Finished
        }
    val endpoint = ref.endpoint
    val agent = AgentCatalog.of(this)
    val own = shownStatus()
    val rank = if (held && own == ShownStatus.Idle) ShownStatus.Done.rank else own?.rank
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
        urgency = if (finished != null) -2 else rank ?: -1,
        shown = own,
        pinned = isPinned,
        canMarkUnread = isAgent && turn > 0 && own != ShownStatus.Done,
        supportsLabels = supportsLabels,
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
                                rows,
                                tab.number,
                                tab.layoutRef,
                                canRename(space.machine),
                            )
                    }
                }
            if (tabs.isEmpty()) {
                null
            } else {
                val ordered = tabs.map { it.second }
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
    return spaces
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

/** The desktop section a row belongs to, by its urgency (own shown status or a sub-agent's, whichever is higher). */
internal fun TerminalItem.bucket(): PriorityBucket =
    when (urgency) {
        ShownStatus.NeedsYou.rank -> PriorityBucket.NeedsYou
        ShownStatus.Done.rank -> PriorityBucket.Done
        ShownStatus.Working.rank -> PriorityBucket.Working
        ShownStatus.Idle.rank -> PriorityBucket.Idle
        else -> PriorityBucket.NoStatus
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
    /** The open terminal the hold rule keeps at Done rank, if any. */
    held: OpenTerminal? = null,
    /** How a host's terminals are grouped; tests and previews inject fixed workspaces. */
    workspaceOf: (SessionState) -> Workspace = { buildWorkspace(it.terminals, it.desktopLayout, it.machineLayouts) },
): TerminalsUiState {
    var totalTerminals = 0
    val groups =
        overview.mapNotNull { (host, session) ->
            val online = session.connection is ConnectionState.Online
            // Finished terminals are never listed (the desktop's Agents panel hides them too).
            val items =
                itemsWithChildren(
                    session.terminals.filter { it.liveness !is Liveness.Finished },
                    online,
                    session.supportsLabels,
                    held?.takeIf { it.hostId == host.id },
                    session.placedAddresses(),
                ) { m -> m == null || session.machines.firstOrNull { it.name == m }?.state == MachineState.Connected }
            totalTerminals += items.sumOf { 1 + it.children.size }
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
                total = items.sumOf { 1 + it.children.size },
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

/**
 * The Priority list: the hosts' rows merged into one list, ordered like the desktop: urgency desc, then the
 * time the state last changed (newest first), then the original order (hosts, machines, core order).
 * Pinned rows come first as their own section, in that same order, and are not repeated below.
 */
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
                        prio.rows.map { PriorityListRow(it, group.hostId, group.label) }
                    }
                }
            }.sortedWith(
                compareByDescending<PriorityListRow> { it.row.item.urgency }.thenByDescending {
                    it.row.item.sinceMs
                        ?: 0L
                },
            )

    // PriorityBucket declares the section order: Pinned, Needs you, Done, Working, Idle, no status.
    fun PriorityListRow.bucket() = if (row.item.pinned) PriorityBucket.Pinned else row.item.bucket()
    return PriorityBucket.entries.mapNotNull { bucket ->
        rows.filter { it.bucket() == bucket }.takeIf { it.isNotEmpty() }?.let { PriorityListSection(bucket, it) }
    }
}

/** Needs-you terminals on one host. */
internal fun HostGroup.needsYouCount(): Int = sections.sumOf { s -> s.items.count { it.needsAttention() } }

/** The open terminal in two-pane (what the hold rule watches). */
@Immutable
internal data class OpenTerminal(
    val hostId: HostId,
    val address: String,
)

/**
 * The desktop's hold rule: looking at a Done agent makes it Idle, but it stays in Done until it is no longer
 * the open one, rather than dropping away under the reader. Any other change moves it at once.
 * Feed it every update with the open terminal and that terminal's own shown status.
 */
internal class HoldRule {
    private var open: OpenTerminal? = null
    private var shownThen: ShownStatus? = null

    /** The terminal to keep at Done rank now, or null. */
    fun update(
        current: OpenTerminal?,
        shown: ShownStatus?,
    ): OpenTerminal? {
        val cur = current.takeIf { shown != null }
        // Arriving, or any change but Done turning Idle, sets what it was.
        if (cur != open || shown != ShownStatus.Idle) {
            open = cur
            shownThen = shown.takeIf { cur != null }
        }
        return cur.takeIf { it != null && shownThen == ShownStatus.Done && shown == ShownStatus.Idle }
    }
}

/**
 * Addresses the desktop layout or the `herdr.space` label places, like [buildWorkspace] does (its first two
 * sources): a terminal with its own pane or space is a top-level row. Anything else is headless.
 */
internal fun SessionState.placedAddresses(): Set<TerminalAddress> {
    val placed = HashSet<TerminalAddress>()

    fun addPanes(
        layout: DesktopLayout?,
        machine: String?,
    ) {
        layout?.spaces?.forEach { sp ->
            sp.tabs.forEach { tab ->
                tab.panes.filterNot { it.isRemote }.forEach {
                    placed += if (machine == null) it else TerminalAddress("$machine/${it.value}")
                }
            }
        }
    }
    addPanes(desktopLayout, null)
    machineLayouts.forEach { (machine, layout) -> addPanes(layout, machine) }
    terminals.filter { !it.labels[HerdrLabels.SPACE].isNullOrBlank() }.forEach { placed += it.address }
    return placed
}

/**
 * Turns one host's live terminals into rows. A terminal whose `parent` is another live, listed terminal of the
 * host and which is headless (not in [placed]: no desktop pane and no `herdr.space` label, so only the start-folder
 * fallback places it) becomes a [ChildItem] of that parent, depth first like the desktop `tree()`, and gets no
 * row of its own. Only the members of a parent cycle become ordinary rows; a terminal leading into a cycle nests under its parent chain. A row's urgency is the highest
 * of its own and its whole subtree's ranks.
 */
internal fun itemsWithChildren(
    live: List<TerminalRecord>,
    writable: Boolean,
    supportsLabels: Boolean,
    held: OpenTerminal?,
    placed: Set<TerminalAddress> = emptySet(),
    /** Whether a write to a terminal of this federated machine (null = the host's own) can go out (rule 7). */
    machineConnected: (String?) -> Boolean = { true },
): List<TerminalItem> {
    val byAddress = live.associateBy { it.address.value }

    fun parentOf(t: TerminalRecord): TerminalRecord? {
        if (t.address in placed) return null
        val p = t.parent?.takeIf { it.isNotBlank() } ?: return null
        val machine = t.address.machine
        // A remote child's relative parent is on its own machine; never fall back to the local terminal.
        val found = if (machine != null && '/' !in p) byAddress["$machine/$p"] else byAddress[p]
        return found?.takeIf { it.address != t.address }
    }

    fun isRoot(t: TerminalRecord): Boolean {
        val seen = hashSetOf(t.address)
        var cur = parentOf(t) ?: return true
        while (true) {
            if (cur.address == t.address) return true
            if (!seen.add(cur.address)) return false
            cur = parentOf(cur) ?: return false
        }
    }
    val roots = live.filter(::isRoot)
    val kids = live.filterNot(::isRoot).groupBy { parentOf(it)!!.address }

    fun subtree(
        t: TerminalRecord,
        depth: Int,
    ): List<ChildItem> =
        kids[t.address].orEmpty().flatMap { k ->
            listOf(
                ChildItem(
                    ref = k.ref,
                    key = "${k.ref.endpoint.hostId.value}/${k.ref.endpoint.session.orEmpty()}/${k.address.value}",
                    title =
                        k.name?.takeIf { it.isNotBlank() } ?: k.template?.takeIf { it.isNotBlank() }
                            ?: k.agentLabel.ifBlank { k.address.value },
                    agentId = AgentCatalog.of(k)?.id,
                    shown = k.childShownStatus(),
                    depth = depth,
                ),
            ) + subtree(k, depth + 1)
        }
    return roots.map { t ->
        val isHeld = held != null && held.address == t.address.value
        val base = t.toItem(writable, supportsLabels && machineConnected(t.address.machine), isHeld)
        val children = subtree(t, 1)
        if (children.isEmpty()) {
            base
        } else {
            base.copy(
                children = children,
                urgency = maxOf(base.urgency, children.maxOf { it.shown?.rank ?: 0 }),
            )
        }
    }
}

/** A pinned row of the Grouped list: its space (for the project name) and host. */
@Immutable
internal data class PinnedGroupedRow(
    val item: TerminalItem,
    val space: SpaceSection,
    val hostId: HostId,
    val hostLabel: String,
)

/** Grouped mode's "Pinned" section: pinned rows in grouped (host, space, tab, row) order, like the desktop's `[...pinned, ...rest]`. */
internal fun pinnedGroupedRows(groups: List<HostGroup>): List<PinnedGroupedRow> =
    groups.flatMap { g ->
        g.sections.flatMap { sec ->
            sec.spaces.flatMap { sp ->
                sp.items.filter { it.pinned }.map { PinnedGroupedRow(it, sp, g.hostId, g.label) }
            }
        }
    }

/** The space without its pinned rows (they show in the Pinned section); null when nothing is left. */
internal fun SpaceSection.withoutPinned(): SpaceSection? {
    if (tabs.none { t -> t.items.any { it.pinned } }) return this
    val left =
        tabs.mapNotNull { t ->
            t.items
                .filterNot { it.pinned }
                .takeIf { it.isNotEmpty() }
                ?.let { t.copy(items = it) }
        }
    return left.takeIf { it.isNotEmpty() }?.let { copy(tabs = it) }
}
