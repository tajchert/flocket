package com.mtajchert.flocket.feature.terminal.impl

import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.DesktopLayout
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.buildWorkspace

/**
 * What the tab strip under the terminal's top bar shows: the tabs of the open terminal's space and,
 * when its tab has several panes, a pane switcher. Pure data with value equality, so the screen only
 * recomposes the strip when it really changes.
 */
@Immutable
data class TabStripUi(
    val tabs: List<StripTab>,
    val panes: List<StripPane>,
) {
    /** The strip has something to switch between. */
    val isVisible: Boolean get() = tabs.size >= 2 || panes.size >= 2
}

@Immutable
data class StripTab(
    val key: String,
    val name: String,
    /** Highest-urgency state among the tab's live terminals; null = nothing reported. */
    val state: AgentState?,
    val selected: Boolean,
    /** The terminal to open when the tab is tapped: its active pane, else its first. */
    val target: String,
)

@Immutable
data class StripPane(
    val address: String,
    /** Reported agent, else the terminal's name, else its program, else its address. */
    val label: String,
    val state: AgentState?,
    val selected: Boolean,
)

/**
 * Derives the strip for [current] from a host's terminals and the desktop layout; null when [current]
 * is unknown or there is nothing to switch between (a single tab with a single pane).
 */
fun buildTabStrip(
    terminals: List<TerminalRecord>,
    layout: DesktopLayout?,
    current: TerminalAddress,
    machineLayouts: Map<String, DesktopLayout> = emptyMap(),
): TabStripUi? = buildTabStrip(buildWorkspace(terminals, layout, machineLayouts), terminals, current)

fun buildTabStrip(
    workspace: Workspace,
    terminals: List<TerminalRecord>,
    current: TerminalAddress,
): TabStripUi? {
    val (space, currentTab) = workspace.locate(current) ?: return null
    val byAddress = terminals.associateBy { it.address }
    val tabs =
        space.tabs.mapNotNull { tab ->
            val target =
                (tab.activePane?.takeIf { it in tab.panes } ?: tab.panes.firstOrNull()) ?: return@mapNotNull null
            StripTab(
                key = tab.key,
                name = tab.name,
                state = tab.topState,
                selected = tab.key == currentTab.key,
                // The open terminal stays the target of its own tab, so re-tapping it is a no-op.
                target = if (tab.key == currentTab.key) current.value else target.value,
            )
        }
    // Panes matter only when there is something to switch between.
    val panes =
        currentTab.panes.takeIf { it.size >= 2 }.orEmpty().map { address ->
            val record = byAddress[address]
            StripPane(
                address = address.value,
                label = record?.paneLabel() ?: address.value,
                state = record?.liveState(),
                selected = address == current,
            )
        }
    return TabStripUi(tabs, panes).takeIf { it.isVisible }
}

/** The agent the program reported, else the terminal's name, else its program, else the address. */
private fun TerminalRecord.paneLabel(): String =
    (status as? AgentStatus.Reported)
        ?.app
        .orEmpty()
        .ifBlank { name.orEmpty() }
        .ifBlank { agentLabel }
        .ifBlank { address.value }

private fun TerminalRecord.liveState(): AgentState? =
    if (liveness is Liveness.Finished) null else (status as? AgentStatus.Reported)?.state
