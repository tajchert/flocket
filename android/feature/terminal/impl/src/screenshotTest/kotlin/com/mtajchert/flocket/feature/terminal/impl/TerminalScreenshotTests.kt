@file:Suppress("ktlint:standard:max-line-length") // Escape-sequence fixtures read best on one line.

package com.mtajchert.flocket.feature.terminal.impl

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.preview.PreviewFormFactors
import com.mtajchert.flocket.core.designsystem.preview.PreviewLargeFont
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.terminal.fake.FakeTerminalEngine
import com.mtajchert.flocket.core.terminal.ui.ExtraKeysRow
import com.mtajchert.flocket.core.terminal.ui.TerminalSnapshot
import com.mtajchert.flocket.core.terminal.ui.TerminalThemes
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.PaneLayout

private const val E = "\u001b"

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
private annotation class PreviewPhone

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewPhoneDark

@Preview(
    name = "Tablet two-pane",
    showBackground = true,
    widthDp = 1280,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewTablet

private fun session(
    cols: Int,
    rows: Int,
    dark: Boolean,
    exited: Boolean = false,
) = FakeTerminalEngine(cols, rows, TerminalThemes.forDarkMode(dark)).run {
    val s = StringBuilder()
    s.append("$E[1;38;5;75m~/code/flocket$E[0m on $E[35m main$E[0m\r\n")
    s.append("$E[32m❯$E[0m claude\r\n\r\n")
    s.append("$E[38;5;208m╭──────────────────────────────────────────╮$E[0m\r\n")
    s.append("$E[38;5;208m│$E[0m $E[1m✻ Welcome to Claude Code$E[0m                 $E[38;5;208m│$E[0m\r\n")
    s.append("$E[38;5;208m│$E[0m   $E[2mcwd: ~/code/flocket$E[0m                    $E[38;5;208m│$E[0m\r\n")
    s.append("$E[38;5;208m╰──────────────────────────────────────────╯$E[0m\r\n\r\n")
    s.append("$E[1m>$E[0m Refactor the auth module to use the new\r\n  token store\r\n\r\n")
    s.append("$E[32m●$E[0m I'll start by reading the current module.\r\n\r\n")
    s.append("$E[32m●$E[0m $E[1mRead$E[0m(core/security/KeyVault.kt)\r\n")
    s.append("  $E[2m⎿  Read 214 lines$E[0m\r\n\r\n")
    s.append("$E[32m●$E[0m $E[1mUpdate$E[0m(core/data/AuthRepository.kt)\r\n")
    s.append("  $E[2m⎿  Updated with 12 additions and 4 removals$E[0m\r\n")
    s.append("     $E[48;5;22;38;5;255m+ private val store = TokenStore(vault)     $E[0m\r\n")
    s.append("     $E[48;5;52;38;5;255m- private var token: String? = null        $E[0m\r\n\r\n")
    if (exited) {
        s.append("$E[32m●$E[0m Done. All 42 tests pass.\r\n\r\n")
        s.append("$E[32m❯$E[0m exit\r\n")
    } else {
        s.append("$E[38;5;208m✻$E[0m Running tests… $E[2m(esc to interrupt)$E[0m\r\n\r\n")
        s.append("$E[2m╭──────────────────────────────────────────╮$E[0m\r\n")
        s.append("$E[2m│$E[0m >                                        $E[2m│$E[0m\r\n")
        s.append("$E[2m╰──────────────────────────────────────────╯$E[0m$E[1A$E[5G")
    }
    feed(s.toString().encodeToByteArray())
    snapshot()
}

private val live =
    TerminalUiState(
        address = "t1",
        hostLabel = "Studio Mac",
        agentLabel = "claude",
        title = "refactor-auth",
        status = SampleData.terminals.first().status,
        connection = ConnectionState.Online(SampleData.coreInfo),
        phase = TerminalPhase.Live,
        streamConnected = true,
        isOwner = true,
    )

@Composable
private fun Shot(
    state: TerminalUiState,
    cols: Int = 50,
    rows: Int = 40,
    extraKeys: Boolean = true,
    exited: Boolean = false,
    tabStrip: TabStripUi? = null,
    header: TerminalHeaderUi = TerminalHeaderUi("flocket", 1, 3, "t2"),
) {
    val dark = isSystemInDarkTheme()
    FlocketTheme(darkTheme = dark, dynamicColor = false) {
        TerminalScreen(
            state = state,
            tabStrip = tabStrip,
            header = header,
            actions = TerminalActions(),
            extraKeys = {
                if (extraKeys) ExtraKeysRow(ctrlActive = false, altActive = false, onKey = {}, onToggleKeyboard = {})
            },
        ) { modifier ->
            TerminalSnapshot(
                session(cols, rows, dark, exited),
                TerminalThemes.forDarkMode(dark),
                modifier,
                fontSizeSp = 13f,
            )
        }
    }
}

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun TerminalLiveScreenshot() = Shot(live)

// Blocked: number replies above the extra keys, nothing else needs the user.
@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@PreviewLargeFont
@Composable
private fun TerminalBlockedScreenshot() =
    Shot(live.copy(status = SampleData.terminals[1].status), header = TerminalHeaderUi("flocket", 1))

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun TerminalSizeTakenScreenshot() = Shot(live.copy(isOwner = false), cols = 90, rows = 60)

@PreviewTest
@PreviewPhoneDark
@Composable
private fun TerminalReconnectingScreenshot() =
    Shot(live.copy(streamConnected = false, connection = ConnectionState.Connecting(1)))

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalExitedScreenshot() =
    Shot(live.copy(phase = TerminalPhase.Exited(0, null), status = SampleData.terminals[2].status), exited = true)

@PreviewTest
@PreviewPhoneDark
@Composable
private fun TerminalWaitingForShellScreenshot() =
    Shot(
        live.copy(
            phase = TerminalPhase.WaitingForShell,
            agentLabel = "zsh",
            title = "",
            status = SampleData.terminals[4].status,
        ),
    )

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalConnectingScreenshot() =
    Shot(
        live.copy(
            phase = TerminalPhase.Connecting,
            streamConnected = false,
            connection = ConnectionState.Connecting(1),
        ),
    )

@PreviewTest
@PreviewTablet
@Composable
private fun TerminalTwoPaneScreenshot() {
    FlocketTheme(darkTheme = true, dynamicColor = false) {
        Row {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.width(360.dp).fillMaxHeight(),
            ) {
                Column {
                    Text(
                        "Terminals",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    SampleData.terminals.take(5).forEachIndexed { i, t ->
                        ListItem(
                            headlineContent = { Text(t.name ?: t.agentLabel) },
                            supportingContent = { Text("${t.agentLabel} · ${t.address}") },
                            trailingContent = { AgentStatusChip(t.status) },
                            colors =
                                ListItemDefaults.colors(
                                    containerColor =
                                        if (i ==
                                            0
                                        ) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                ),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) { Shot(live, cols = 110, rows = 40, extraKeys = false) }
        }
    }
}

private fun tabsOf(
    vararg names: String,
    selected: Int = 0,
) = names.mapIndexed { i, name ->
    StripTab(
        key = "tab$i",
        name = name,
        state =
            listOf(AgentState.Working, AgentState.Blocked, null, AgentState.Done, AgentState.Idle, AgentState.Error)[
                i %
                    6,
            ],
        selected = i == selected,
        target = "t$i",
    )
}

private fun panesOf(
    vararg labels: String,
    selected: Int = 0,
) = labels.mapIndexed { i, label ->
    StripPane("t$i", label, listOf(AgentState.Working, null, AgentState.Blocked)[i % 3], i == selected)
}

/** A terminal screen shaped like the app at this window: multi-pane (sidebar toggle) from 600dp wide. */
@Composable
private fun StripShot(tabStrip: TabStripUi) {
    val config = LocalConfiguration.current
    val width = config.screenWidthDp
    CompositionLocalProvider(
        LocalPaneLayout provides PaneLayout(multiPane = width >= 600, compactHeight = config.screenHeightDp < 480),
    ) {
        Shot(live, cols = (width / 8).coerceIn(40, 200), rows = 40, extraKeys = width < 600, tabStrip = tabStrip)
    }
}

@PreviewTest
@PreviewFormFactors
@Composable
private fun TerminalTabStripTwoTabsScreenshot() =
    StripShot(TabStripUi(tabsOf("server", "tests", selected = 0), emptyList()))

@PreviewTest
@PreviewFormFactors
@Composable
private fun TerminalTabStripManyTabsScreenshot() =
    StripShot(
        TabStripUi(
            tabsOf(
                "server",
                "tests",
                "docs",
                "release notes",
                "database",
                "frontend",
                "infra",
                "scratch",
                selected = 6,
            ),
            emptyList(),
        ),
    )

@PreviewTest
@PreviewFormFactors
@Composable
private fun TerminalPaneSwitcherScreenshot() =
    StripShot(
        TabStripUi(
            tabsOf("agents", "logs", selected = 0),
            panesOf("claude", "codex", "zsh", selected = 0),
        ),
    )
