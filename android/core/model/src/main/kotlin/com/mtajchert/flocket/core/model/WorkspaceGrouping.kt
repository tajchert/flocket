package com.mtajchert.flocket.core.model

/**
 * Groups one host's [terminals] (in core order) into a [Workspace], like the desktop app does.
 *
 * The host's own terminals are grouped first, then each federated machine's (`rohan/t7` belongs to
 * machine `rohan`; machines in order of first appearance). Within one scope each terminal is placed
 * by the first source that knows it:
 * 1. the desktop layout: [layout] for local terminals, `machineLayouts[machine]` for that machine's
 *    (its panes are machine-local, `t7` there means `rohan/t7`). A layout never places terminals of
 *    another scope. Spaces and tabs in document order, panes in layout order. Layout panes whose
 *    terminal no longer exists are dropped, then empty tabs and spaces. A terminal listed twice stays
 *    in its first pane.
 * 2. the `herdr.space` / `herdr.tab` labels ([HerdrLabels]). A label space whose id matches a desktop
 *    space joins that space (as the desktop app would show it); other label spaces follow the desktop
 *    ones in order of first appearance.
 * 3. the start folder ([TerminalRecord.cwd], not the foreground one, so rows don't jump). A terminal
 *    whose folder equals (ignoring trailing `/`) the shown folder of a desktop or label space of its
 *    scope joins the first such space, in one trailing implicit tab. Any other gets one
 *    [SpaceSource.Folder] space with one implicit tab per folder, in order of first appearance.
 *
 * A desktop or label space shows its first terminal's live folder ([TerminalRecord.displayCwd]), like
 * the desktop app (SPEC §9.1). The document's `folder` is only a fallback for a space with no live
 * terminal: it is where the space was created and goes stale once its terminals run elsewhere.
 * Unnamed spaces are named after the shown folder.
 *
 * Names may be blank (an unnamed desktop or label space without a folder, an unnamed tab): the UI
 * shows "Space [WorkspaceSpace.number]" / the tab's [WorkspaceTab.number]. Machine spaces have keys
 * prefixed with the machine and carry [WorkspaceSpace.machine]. Pure and deterministic.
 */
fun buildWorkspace(
    terminals: List<TerminalRecord>,
    layout: DesktopLayout?,
    machineLayouts: Map<String, DesktopLayout> = emptyMap(),
): Workspace {
    val records = LinkedHashMap<TerminalAddress, TerminalRecord>()
    for (t in terminals) records.putIfAbsent(t.address, t)
    val keys = KeyAllocator()
    val machines = records.keys.mapNotNull { it.machine }.distinct()
    val spaces = mutableListOf<SpaceDraft>()
    for (machine in listOf<String?>(null) + machines) {
        val scope = records.values.filter { it.address.machine == machine }
        spaces += groupScope(machine, if (machine == null) layout else machineLayouts[machine], scope, keys)
    }
    return Workspace(
        spaces
            .map { it.build(records) }
            .filter { it.tabs.isNotEmpty() }
            .mapIndexed { i, s -> s.copy(number = i + 1) },
    )
}

/** Groups the terminals of one [machine] (null = the host itself) using that scope's [layout]. */
private fun groupScope(
    machine: String?,
    layout: DesktopLayout?,
    scope: List<TerminalRecord>,
    keys: KeyAllocator,
): List<SpaceDraft> {
    val inScope = scope.mapTo(HashSet()) { it.address }
    val records = scope.associateBy { it.address }
    val prefix = machine?.let { "$it:" }.orEmpty()
    val placed = HashSet<TerminalAddress>()
    val spaces = mutableListOf<SpaceDraft>()
    val seenSpaceIds = HashSet<String>()

    // 1. Desktop layout.
    layout?.spaces?.forEachIndexed { si, ds ->
        val space =
            SpaceDraft(
                key = keys.next("desktop:$prefix${ds.id.ifBlank { "#$si" }}"),
                id = ds.id,
                name = ds.name.trim(),
                folder = ds.folder?.takeIf { it.isNotBlank() },
                source = SpaceSource.Desktop,
                machine = machine,
            )
        // A blank or repeated document id cannot be addressed, so it gets no ref.
        space.hasDocumentId = ds.id.isNotBlank() && seenSpaceIds.add(ds.id)
        space.documentTabCount = ds.tabs.size
        val seenTabIds = HashSet<String>()
        ds.tabs.forEachIndexed { ti, dt ->
            val tab = space.tab(dt.id.ifBlank { "#$ti" }, dt.name.trim())
            if (dt.id.isNotBlank() && seenTabIds.add(dt.id)) tab.documentTabId = dt.id
            if (tab.documentNumber == null) tab.documentNumber = ti + 1
            for (local in dt.panes) {
                if (local.isRemote) continue
                val p = if (machine == null) local else TerminalAddress("$machine/${local.value}")
                if (p !in inScope || !placed.add(p)) continue
                tab.panes += p
            }
            tab.activePane =
                dt.activePane
                    ?.takeIf { !it.isRemote }
                    ?.let { if (machine == null) it else TerminalAddress("$machine/${it.value}") }
                    ?.takeIf { it in tab.panes }
        }
        spaces += space
    }

    // 2. Labels (terminals the layout did not place).
    val desktopById = HashMap<String, SpaceDraft>()
    for (s in spaces) if (s.id.isNotBlank()) desktopById.putIfAbsent(s.id, s)
    val labelSpaces = LinkedHashMap<String, SpaceDraft>()
    for (t in scope) {
        if (t.address in placed) continue
        val spaceId = t.labels[HerdrLabels.SPACE]?.takeIf { it.isNotBlank() } ?: continue
        val space =
            desktopById[spaceId] ?: labelSpaces.getOrPut(spaceId) {
                SpaceDraft(
                    key = keys.next("label:$prefix$spaceId"),
                    id = spaceId,
                    name = "",
                    folder = t.cwd.takeIf { it.isNotBlank() },
                    source = SpaceSource.Labels,
                    machine = machine,
                )
            }
        val tabId = t.labels[HerdrLabels.TAB]?.takeIf { it.isNotBlank() }.orEmpty()
        space.tab(tabId, "").panes += t.address
        placed += t.address
    }
    spaces += labelSpaces.values

    // 3. Start folder: join the first space with that folder, else one Folder space per folder.
    val known = spaces.map { it to normalizedFolder(it.shownFolder(records)) }
    val folderSpaces = LinkedHashMap<String, SpaceDraft>()
    for (t in scope) {
        if (t.address in placed) continue
        val joined = normalizedFolder(t.cwd)?.let { f -> known.firstOrNull { it.second == f }?.first }
        if (joined != null) {
            joined.implicitTab().panes += t.address
            continue
        }
        val space =
            folderSpaces.getOrPut(t.cwd) {
                SpaceDraft(
                    key = keys.next("folder:${machine.orEmpty()}:${t.cwd}"),
                    id = "",
                    name = "",
                    folder = t.cwd.takeIf { it.isNotBlank() },
                    source = SpaceSource.Folder,
                    machine = machine,
                )
            }
        space.implicitTab().panes += t.address
    }
    spaces += folderSpaces.values
    return spaces
}

/** [path] without trailing slashes; null when nothing is left (blank or `/`). */
private fun normalizedFolder(path: String?): String? = path?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

/** Key base of the trailing implicit tab (folder groups and folder-joined terminals). */
private const val FOLDER_TAB = "all"

/** Display name for [path]: its last segment; `~` and `/` stay as they are; null when blank. */
fun folderName(path: String?): String? {
    val p = path?.trim().orEmpty()
    if (p.isEmpty()) return null
    val trimmed = p.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return p.take(1)
    return trimmed.substringAfterLast('/').substringAfterLast('\\').ifEmpty { trimmed }
}

internal fun List<TerminalRecord>.topState(): AgentState? =
    asSequence()
        .filter { it.liveness !is Liveness.Finished }
        .mapNotNull { (it.status as? AgentStatus.Reported)?.state }
        .maxByOrNull { it.priorityRank }

/** Unique keys within one workspace (a damaged document may repeat ids). */
private class KeyAllocator {
    private val used = HashSet<String>()

    fun next(base: String): String {
        if (used.add(base)) return base
        var n = 2
        while (!used.add("$base~$n")) n++
        return "$base~$n"
    }
}

private class TabDraft(
    val key: String,
    val name: String,
) {
    val panes = mutableListOf<TerminalAddress>()
    var activePane: TerminalAddress? = null

    /** Set only for a tab that came from a usable `herdr-app` tab id. */
    var documentTabId: String? = null

    /** 1-based place in the document's tab list (counting every document tab), null for others. */
    var documentNumber: Int? = null
}

private class SpaceDraft(
    val key: String,
    val id: String,
    val name: String,
    val folder: String?,
    val source: SpaceSource,
    val machine: String?,
) {
    val tabs = mutableListOf<TabDraft>()

    /** True only for a desktop space whose [id] is non-blank and first in its scope. */
    var hasDocumentId = false

    /** How many tabs the document lists for this space; other tabs are numbered after them. */
    var documentTabCount = 0
    private val tabKeys = KeyAllocator()
    private val byId = HashMap<String, TabDraft>()

    /** The tab with [id] (created at the end when new); a desktop tab id is unique per space. */
    fun tab(
        id: String,
        name: String,
    ): TabDraft =
        byId.getOrPut(id) {
            TabDraft(tabKeys.next(id.ifEmpty { "-" }), name).also { tabs += it }
        }

    /** The one unnamed tab appended after the others for folder terminals (never a desktop tab id). */
    fun implicitTab(): TabDraft =
        implicit ?: TabDraft(tabKeys.next(FOLDER_TAB), "").also {
            implicit = it
            tabs += it
        }

    private var implicit: TabDraft? = null

    /**
     * The folder to show: for a desktop or label space its first terminal's live folder, else the
     * stored [folder]. A [SpaceSource.Folder] space keeps its start folder (it is the grouping key).
     */
    fun shownFolder(records: Map<TerminalAddress, TerminalRecord>): String? {
        if (source == SpaceSource.Folder) return folder
        val first = tabs.firstNotNullOfOrNull { t -> t.panes.firstNotNullOfOrNull { records[it] } }
        return first?.displayCwd?.takeIf { it.isNotBlank() } ?: folder
    }

    fun build(records: Map<TerminalAddress, TerminalRecord>): WorkspaceSpace {
        var extra = documentTabCount
        val builtTabs =
            tabs.filter { it.panes.isNotEmpty() }.map { t ->
                WorkspaceTab(
                    key = t.key,
                    name = t.name,
                    panes = t.panes.toList(),
                    activePane = t.activePane,
                    topState = t.panes.mapNotNull { records[it] }.topState(),
                    number = t.documentNumber ?: ++extra,
                    layoutRef =
                        t.documentTabId
                            ?.takeIf { hasDocumentId }
                            ?.let { LayoutRef(machine, id, it) },
                )
            }
        val shown = shownFolder(records)
        return WorkspaceSpace(
            key = key,
            name = name.ifEmpty { folderName(shown).orEmpty() },
            folder = shown,
            source = source,
            machine = machine,
            tabs = builtTabs,
            topState = builtTabs.flatMap { it.panes }.mapNotNull { records[it] }.topState(),
            number = 0,
            layoutRef = if (hasDocumentId) LayoutRef(machine, id) else null,
            givenName = if (source == SpaceSource.Desktop) name else "",
        )
    }
}
