package com.mtajchert.flocket.core.model

/**
 * The desktop app's layout as read from the `herdr-app` document (protocol/SPEC.md §9.1), flattened
 * for display. Read-only: Flocket never writes it. Pane trees are flattened to their terminal
 * leaves in document order; other leaf kinds (`mod`) are dropped.
 */
data class DesktopLayout(
    val spaces: List<DesktopSpace>,
)

data class DesktopSpace(
    val id: String,
    /** Blank when the desktop user never named it. */
    val name: String,
    /** The space's folder, if set. */
    val folder: String?,
    val tabs: List<DesktopTab>,
)

data class DesktopTab(
    val id: String,
    /** Blank when unnamed. */
    val name: String,
    /** Terminal addresses of the tab's panes, in document order (local addresses such as `t7`). */
    val panes: List<TerminalAddress>,
    /** The focused pane, following the `active` chain; null when unknown. */
    val activePane: TerminalAddress?,
)

/** Where a [WorkspaceSpace] came from (see [buildWorkspace]). */
enum class SpaceSource {
    /** The `herdr-app` document. */
    Desktop,

    /** Only the `herdr.space` / `herdr.tab` terminal labels. */
    Labels,

    /** No layout: terminals that share a start folder. */
    Folder,
}

/**
 * One host's terminals grouped like the desktop app: spaces, then tabs, then panes. Every terminal of
 * the host appears in exactly one tab. Federated terminals are grouped per [WorkspaceSpace.machine].
 */
data class Workspace(
    val spaces: List<WorkspaceSpace>,
) {
    /** The space and tab that hold [address], if any. */
    fun locate(address: TerminalAddress): Pair<WorkspaceSpace, WorkspaceTab>? {
        for (space in spaces) {
            for (tab in space.tabs) {
                if (address in tab.panes) return space to tab
            }
        }
        return null
    }

    companion object {
        val Empty = Workspace(emptyList())
    }
}

data class WorkspaceSpace(
    /** Stable within a host: `desktop:<id>`, `label:<id>` or `folder:<machine>:<path>`. */
    val key: String,
    /** Display name; never blank (falls back to the folder's last segment, else "Space N"). */
    val name: String,
    val folder: String?,
    val source: SpaceSource,
    /** Federated machine for remote terminals, null for the host's own. */
    val machine: String?,
    val tabs: List<WorkspaceTab>,
    /** Highest [AgentState.urgency] among its non-finished terminals with a reported status; null = none. */
    val topState: AgentState?,
) {
    val terminalCount: Int get() = tabs.sumOf { it.panes.size }
}

data class WorkspaceTab(
    /** Stable within its space. */
    val key: String,
    /** Display name; never blank (falls back to "Tab N", N = its 1-based place in the space). */
    val name: String,
    /** Terminal addresses in layout order; only terminals that exist on the host. */
    val panes: List<TerminalAddress>,
    val activePane: TerminalAddress?,
    val topState: AgentState?,
)

/** Label keys the desktop app puts on terminals it created (SPEC §9.1). Read-only for Flocket. */
object HerdrLabels {
    const val SPACE = "herdr.space"
    const val TAB = "herdr.tab"
}
