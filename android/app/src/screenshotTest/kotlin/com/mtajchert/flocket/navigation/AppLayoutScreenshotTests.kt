package com.mtajchert.flocket.navigation

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.TerminalSnapshot
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.PaneCard
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import com.mtajchert.flocket.core.ui.nav.PanePosition
import com.mtajchert.flocket.core.ui.nav.PaneRole
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute
import com.mtajchert.flocket.feature.terminal.impl.TerminalActions
import com.mtajchert.flocket.feature.terminal.impl.TerminalHeaderUi
import com.mtajchert.flocket.feature.terminal.impl.TerminalPhase
import com.mtajchert.flocket.feature.terminal.impl.TerminalScreen
import com.mtajchert.flocket.feature.terminal.impl.TerminalUiState
import com.mtajchert.flocket.feature.terminals.api.TerminalsRoute
import com.mtajchert.flocket.feature.terminals.impl.TerminalsActions
import com.mtajchert.flocket.feature.terminals.impl.TerminalsScreen
import com.mtajchert.flocket.feature.terminals.impl.previewTerminalsUiState

// The whole multi-pane window as FlocketNavHost lays it out (rail, list card, terminal card), from the real
// screens. FlocketNavHost itself needs Hilt and a back stack, so the panes are placed here by hand with the
// sidebar widths of paneScaffoldDirective. The README screenshots come from these references.

@Preview(name = "Tablet", showBackground = true, widthDp = 1280, heightDp = 800, apiLevel = 36)
@Preview(
    name = "Tablet dark",
    showBackground = true,
    widthDp = 1280,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewTablet

/** The unfolded inner display: a medium-width window, which still gets two panes. */
@Preview(name = "Foldable", showBackground = true, widthDp = 673, heightDp = 841, apiLevel = 36)
@Preview(
    name = "Foldable dark",
    showBackground = true,
    widthDp = 673,
    heightDp = 841,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewFoldable

private fun agent(
    address: String,
    folder: String,
    status: AgentStatus.Reported,
    name: String? = null,
    seen: Long? = null,
    turn: Long = 1,
    parent: String? = null,
) = SampleData
    .terminal(
        address = address,
        name = name,
        argv = listOf("/usr/local/bin/${status.app}"),
        status = status,
        cwd = "/Users/mtajchert/code/$folder",
    ).copy(
        turn = turn,
        parent = parent,
        labels = listOfNotNull(seen?.let { "herdr.seen" to it.toString() }).toMap(),
    )

private fun reported(
    state: AgentState,
    app: String = "claude",
    kind: BlockKind? = null,
    progress: Int? = null,
    message: String? = null,
) = AgentStatus.Reported(state, state.name.lowercase(), kind, progress, app, null, message)

/** One host with agents in every state the list sorts by; the open one is working. */
private val agents =
    previewTerminalsUiState(
        listOf(
            HostOverview(
                SampleData.studio,
                SampleData.studioOnline.copy(
                    machines = emptyList(),
                    terminals =
                        listOf(
                            agent("t1", "flocket", reported(AgentState.Working, progress = 62), name = "auth"),
                            agent(
                                "t2",
                                "docs",
                                reported(
                                    AgentState.Blocked,
                                    "codex",
                                    BlockKind.Permission,
                                    message = "Allow `npm test`?",
                                ),
                            ),
                            agent("t3", "api", reported(AgentState.Done), turn = 3, seen = 2),
                            agent("t4", "cli", reported(AgentState.Working, "codex")),
                            agent("t5", "cli", reported(AgentState.Working), name = "reviewer", parent = "t4"),
                            agent("t6", "web", reported(AgentState.Idle), turn = 2, seen = 2),
                        ),
                ),
            ),
        ),
    )

private val open =
    TerminalUiState(
        address = "t1",
        hostLabel = "Studio Mac",
        agentLabel = "claude",
        title = "auth",
        status = reported(AgentState.Working, progress = 62),
        connection = ConnectionState.Online(SampleData.coreInfo),
        phase = TerminalPhase.Live,
        streamConnected = true,
        isOwner = true,
    )

@Composable
private fun AppLayout(
    sidebarWidth: Dp,
    cols: Int,
    rows: Int,
    fontSizeSp: Float,
) {
    val dark = isSystemInDarkTheme()
    FlocketTheme(darkTheme = dark, dynamicColor = false) {
        CompositionLocalProvider(LocalPaneLayout provides PaneLayout(multiPane = true, navigationRail = true)) {
            Surface(color = NavigationSuiteScaffoldDefaults.containerColor) {
                Row {
                    FlocketNavigationRail(TerminalsRoute, needsYouCount = 1, onSelect = {}, onNewTerminal = {})
                    PaneCard(PanePosition.Leading, PaneRole.List, Modifier.width(sidebarWidth)) {
                        TerminalsScreen(
                            agents,
                            TerminalsActions(),
                            selected = TerminalRoute("studio", "t1"),
                            nowMs = SampleData.NOW_MS,
                        )
                    }
                    PaneCard(PanePosition.Trailing, PaneRole.Detail, Modifier.weight(1f)) {
                        TerminalScreen(
                            state = open,
                            actions = TerminalActions(),
                            showBack = false,
                            header = TerminalHeaderUi("flocket", 1, 1, "t2"),
                        ) { modifier ->
                            val theme = TerminalThemes.forDarkMode(dark)
                            val engine = FakeTerminalEngine(cols, rows, theme)
                            engine.feed(SampleData.claudeCodeTranscript().encodeToByteArray())
                            TerminalSnapshot(engine.snapshot(), theme, modifier, fontSizeSp = fontSizeSp)
                        }
                    }
                }
            }
        }
    }
}

@PreviewTest
@PreviewTablet
@Composable
private fun AppLayoutTabletScreenshot() = AppLayout(sidebarWidth = 300.dp, cols = 100, rows = 40, fontSizeSp = 13f)

@PreviewTest
@PreviewFoldable
@Composable
private fun AppLayoutFoldableScreenshot() = AppLayout(sidebarWidth = 280.dp, cols = 50, rows = 60, fontSizeSp = 10f)
