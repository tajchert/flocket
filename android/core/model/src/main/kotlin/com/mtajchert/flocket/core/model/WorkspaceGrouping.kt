package com.mtajchert.flocket.core.model

/**
 * Groups one host's [terminals] into a [Workspace] (rulings in the adaptive-workspace design):
 * the desktop [layout] first, then `herdr.*` labels, then shared start folder.
 *
 * PLACEHOLDER: folder grouping only. The full implementation replaces this body; the signature is
 * the contract the UI codes against.
 */
fun buildWorkspace(
    terminals: List<TerminalRecord>,
    @Suppress("UNUSED_PARAMETER") layout: DesktopLayout?,
): Workspace {
    val groups = LinkedHashMap<Pair<String?, String>, MutableList<TerminalRecord>>()
    for (t in terminals) groups.getOrPut(t.address.machine to t.cwd) { mutableListOf() }.add(t)
    return Workspace(
        groups.map { (key, records) ->
            val (machine, cwd) = key
            val state = records.topState()
            WorkspaceSpace(
                key = "folder:${machine.orEmpty()}:$cwd",
                name = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd.ifEmpty { "~" } },
                folder = cwd.ifEmpty { null },
                source = SpaceSource.Folder,
                machine = machine,
                tabs =
                    listOf(
                        WorkspaceTab(
                            key = "all",
                            name = "",
                            panes = records.map { it.address },
                            activePane = null,
                            topState = state,
                        ),
                    ),
                topState = state,
            )
        },
    )
}

internal fun List<TerminalRecord>.topState(): AgentState? =
    asSequence()
        .filter { it.liveness !is Liveness.Finished }
        .mapNotNull { (it.status as? AgentStatus.Reported)?.state }
        .maxByOrNull { it.urgency }
