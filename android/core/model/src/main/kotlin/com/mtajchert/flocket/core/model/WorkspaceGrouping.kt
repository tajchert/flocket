package com.mtajchert.flocket.core.model

/**
 * Groups one host's [terminals] (in core order) into a [Workspace], like the desktop app does.
 *
 * Each terminal is placed by the first source that knows it:
 * 1. the desktop [layout] (`herdr-app`, local machine only): spaces and tabs in document order,
 *    panes in layout order. Layout panes whose terminal no longer exists are dropped, then empty
 *    tabs and spaces. A terminal listed twice stays in its first pane.
 * 2. the `herdr.space` / `herdr.tab` labels ([HerdrLabels]). A label space whose id matches a desktop
 *    space joins that space (as the desktop app would show it); other label spaces follow the desktop
 *    ones in order of first appearance.
 * 3. otherwise the start folder ([TerminalRecord.cwd], not the foreground one, so rows don't jump):
 *    one [SpaceSource.Folder] space with one implicit tab per folder, in order of first appearance.
 *
 * Federated terminals (`rohan/t7`) never use the layout or labels: they are folder-grouped per
 * machine, after all local spaces. Names are never blank: the given name, else the folder's last
 * segment, else "Space N" / "Tab N" (N = 1-based position). Pure and deterministic.
 */
fun buildWorkspace(
    terminals: List<TerminalRecord>,
    layout: DesktopLayout?,
): Workspace {
    val records = LinkedHashMap<TerminalAddress, TerminalRecord>()
    for (t in terminals) records.putIfAbsent(t.address, t)
    val placed = HashSet<TerminalAddress>()
    val spaces = mutableListOf<SpaceDraft>()
    val keys = KeyAllocator()

    // 1. Desktop layout (local terminals only).
    layout?.spaces?.forEachIndexed { si, ds ->
        val space =
            SpaceDraft(
                key = keys.next("desktop:${ds.id.ifBlank { "#$si" }}"),
                id = ds.id,
                name = ds.name.trim(),
                folder = ds.folder?.takeIf { it.isNotBlank() },
                source = SpaceSource.Desktop,
                machine = null,
            )
        ds.tabs.forEachIndexed { ti, dt ->
            val tab = space.tab(dt.id.ifBlank { "#$ti" }, dt.name.trim(), ti)
            for (p in dt.panes) {
                if (p.isRemote || p !in records || !placed.add(p)) continue
                tab.panes += p
            }
            tab.activePane = dt.activePane?.takeIf { it in tab.panes }
        }
        spaces += space
    }

    // 2. Labels (local terminals the layout did not place).
    val desktopById = HashMap<String, SpaceDraft>()
    for (s in spaces) if (s.id.isNotBlank()) desktopById.putIfAbsent(s.id, s)
    val labelSpaces = LinkedHashMap<String, SpaceDraft>()
    for (t in records.values) {
        if (t.address.isRemote || t.address in placed) continue
        val spaceId = t.labels[HerdrLabels.SPACE]?.takeIf { it.isNotBlank() } ?: continue
        val space =
            desktopById[spaceId] ?: labelSpaces.getOrPut(spaceId) {
                SpaceDraft(
                    key = keys.next("label:$spaceId"),
                    id = spaceId,
                    name = "",
                    folder = t.cwd.takeIf { it.isNotBlank() },
                    source = SpaceSource.Labels,
                    machine = null,
                )
            }
        val tabId = t.labels[HerdrLabels.TAB]?.takeIf { it.isNotBlank() }.orEmpty()
        space.tab(tabId, "", space.tabs.size).panes += t.address
        placed += t.address
    }
    spaces += labelSpaces.values

    // 3. Start folder: local groups first, then each machine's (machines in first-appearance order).
    val folderSpaces = LinkedHashMap<Pair<String?, String>, SpaceDraft>()
    val unplaced = records.values.filter { it.address !in placed }
    for (t in unplaced.sortedBy { if (it.address.isRemote) 1 else 0 }) {
        val machine = t.address.machine
        val space =
            folderSpaces.getOrPut(machine to t.cwd) {
                SpaceDraft(
                    key = keys.next("folder:${machine.orEmpty()}:${t.cwd}"),
                    id = "",
                    name = "",
                    folder = t.cwd.takeIf { it.isNotBlank() },
                    source = SpaceSource.Folder,
                    machine = machine,
                )
            }
        space.tab(FOLDER_TAB, "", 0).panes += t.address
    }
    spaces += folderSpaces.values

    return Workspace(
        spaces
            .map { it.build(records) }
            .filter { it.tabs.isNotEmpty() }
            .mapIndexed { i, s -> if (s.name.isEmpty()) s.copy(name = "Space ${i + 1}") else s },
    )
}

/** Key of the one implicit tab of a [SpaceSource.Folder] space. */
private const val FOLDER_TAB = "all"

/** Display name for [path]: its last segment; `~` and `/` stay as they are; null when blank. */
internal fun folderName(path: String?): String? {
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
        .maxByOrNull { it.urgency }

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
    /** 0-based position used for "Tab N" when unnamed. */
    val position: Int,
) {
    val panes = mutableListOf<TerminalAddress>()
    var activePane: TerminalAddress? = null
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
    private val tabKeys = KeyAllocator()
    private val byId = HashMap<String, TabDraft>()

    /** The tab with [id] (created at the end when new); a desktop tab id is unique per space. */
    fun tab(
        id: String,
        name: String,
        position: Int,
    ): TabDraft =
        byId.getOrPut(id) {
            TabDraft(tabKeys.next(id.ifEmpty { "-" }), name, position).also { tabs += it }
        }

    fun build(records: Map<TerminalAddress, TerminalRecord>): WorkspaceSpace {
        val builtTabs =
            tabs.filter { it.panes.isNotEmpty() }.map { t ->
                WorkspaceTab(
                    key = t.key,
                    name = t.name.ifEmpty { "Tab ${t.position + 1}" },
                    panes = t.panes.toList(),
                    activePane = t.activePane,
                    topState = t.panes.mapNotNull { records[it] }.topState(),
                )
            }
        return WorkspaceSpace(
            key = key,
            name = name.ifEmpty { folderName(folder).orEmpty() },
            folder = folder,
            source = source,
            machine = machine,
            tabs = builtTabs,
            topState = builtTabs.flatMap { it.panes }.mapNotNull { records[it] }.topState(),
        )
    }
}
