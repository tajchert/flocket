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
    /** Where the space was created, if set. Goes stale; the UI prefers its first terminal's live folder. */
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

/** A space, or a tab inside it, as it exists in a `herdr-app` document (SPEC §9.1). */
data class LayoutRef(
    val machine: String?,
    val spaceId: String,
    val tabId: String? = null,
)

data class WorkspaceSpace(
    /** Stable within a host: `desktop:<id>`, `label:<id>` or `folder:<machine>:<path>`. */
    val key: String,
    /**
     * Display name: the given name, else the [folder]'s last segment. Blank when neither exists; the
     * UI then shows "Space [number]".
     */
    val name: String,
    /**
     * The folder to show: for desktop and label spaces the first terminal's live folder (the
     * document's folder only when no terminal has one), for [SpaceSource.Folder] the start folder.
     */
    val folder: String?,
    val source: SpaceSource,
    /** Federated machine for remote terminals, null for the host's own. */
    val machine: String?,
    val tabs: List<WorkspaceTab>,
    /** Highest [AgentState.priorityRank] among its non-finished terminals with a reported status; null = none. */
    val topState: AgentState?,
    /** 1-based position among all spaces of the workspace. */
    val number: Int,
    /** The document space this is, when it is one with a usable id; null for label and folder spaces. */
    val layoutRef: LayoutRef? = null,
    /** The document's trimmed name; blank when unnamed ([name] is the display name). */
    val givenName: String = "",
) {
    val terminalCount: Int get() = tabs.sumOf { it.panes.size }
}

data class WorkspaceTab(
    /** Stable within its space. */
    val key: String,
    /** Display name; blank when unnamed, the UI then shows just the number. */
    val name: String,
    /** Terminal addresses in layout order; only terminals that exist on the host. */
    val panes: List<TerminalAddress>,
    val activePane: TerminalAddress?,
    val topState: AgentState?,
    /** 1-based desktop tab number: place in the document tab list; other tabs follow its count. */
    val number: Int,
    /** The document tab this is, when it has a usable id; null for implicit and label-only tabs. */
    val layoutRef: LayoutRef? = null,
)

/** Label keys the desktop app puts on terminals it created (SPEC §9.1). Read-only for Flocket. */
object HerdrLabels {
    const val SPACE = "herdr.space"
    const val TAB = "herdr.tab"
}
