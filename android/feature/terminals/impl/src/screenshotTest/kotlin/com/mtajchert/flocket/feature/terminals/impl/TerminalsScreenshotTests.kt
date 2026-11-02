@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.preview.PreviewFormFactors
import com.mtajchert.flocket.core.designsystem.preview.PreviewLargeFont
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.SpaceSource
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalSort
import com.mtajchert.flocket.core.model.TerminalTemplate
import com.mtajchert.flocket.core.model.Workspace
import com.mtajchert.flocket.core.model.WorkspaceSpace
import com.mtajchert.flocket.core.model.WorkspaceTab
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.nav.LocalPaneLayout
import com.mtajchert.flocket.core.ui.nav.PaneCard
import com.mtajchert.flocket.core.ui.nav.PaneLayout
import com.mtajchert.flocket.core.ui.nav.PanePosition
import com.mtajchert.flocket.core.ui.nav.PaneRole
import com.mtajchert.flocket.feature.terminal.api.TerminalRoute

private const val NOW = SampleData.NOW_MS

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewPhoneDark

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
private annotation class PreviewPhone

/** The 300dp sidebar pane next to a terminal. */
@Preview(name = "Sidebar", showBackground = true, widthDp = 300, heightDp = 800, apiLevel = 36)
private annotation class PreviewSidebar

@Preview(
    name = "Sidebar dark",
    showBackground = true,
    widthDp = 300,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewSidebarDark

@Composable
private fun Shot(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) = FlocketTheme(dynamicColor = false) { Surface { content() } }

private val grouped = ListControls(sort = TerminalSort.Grouped)

private val mixed = buildUiState(SampleData.overview, grouped, refreshing = false)

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun TerminalsListScreenshot() =
    Shot { TerminalsScreen(mixed, TerminalsActions(), selected = TerminalRoute("studio", "t1"), nowMs = NOW) }

private fun tab(
    key: String,
    name: String,
    vararg panes: String,
    top: AgentState? = null,
) = WorkspaceTab(key, name, panes.map(::TerminalAddress), null, top, 1)

private fun space(
    key: String,
    name: String,
    folder: String?,
    source: SpaceSource,
    top: AgentState?,
    vararg tabs: WorkspaceTab,
) = WorkspaceSpace(key, name, folder, source, null, tabs.toList(), top, 1)

/** Studio terminals as the desktop app lays them out: a space with two tabs, a one-tab space, a loose one. */
private val desktopWorkspace =
    Workspace(
        listOf(
            space(
                "desktop:a",
                "Flocket",
                "/Users/mtajchert/code/flocket",
                SpaceSource.Desktop,
                AgentState.Blocked,
                tab("1", "agents", "t1", "t2", top = AgentState.Blocked),
                tab("2", "scratch", "t4", "t5", top = AgentState.Idle),
            ),
            space(
                "desktop:b",
                "Docs site",
                "/Users/mtajchert/code/docs",
                SpaceSource.Desktop,
                AgentState.Done,
                tab("1", "main", "t3", top = AgentState.Done),
            ),
            WorkspaceSpace(
                "folder:rohan:/srv/bench",
                "bench",
                "/srv/bench",
                SpaceSource.Folder,
                "rohan",
                listOf(tab("all", "", "rohan/t7", top = AgentState.Error)),
                AgentState.Error,
                9,
            ),
        ),
    )

/** Only `herdr.space` / `herdr.tab` labels: names, no folder subtitle. */
private val labelsWorkspace =
    Workspace(
        listOf(
            space(
                "label:s1",
                "api",
                null,
                SpaceSource.Labels,
                AgentState.Blocked,
                tab("t1", "backend", "t1", "t2", top = AgentState.Blocked),
                tab("t2", "tests", "t3", top = AgentState.Done),
            ),
            space("label:s2", "notes", null, SpaceSource.Labels, null, tab("t1", "shell", "t4", "t5")),
        ),
    )

/** No layout at all: one implicit tab per start folder. */
private val folderWorkspace =
    Workspace(
        listOf(
            space(
                "folder::/Users/mtajchert/code/flocket",
                "flocket",
                "/Users/mtajchert/code/flocket",
                SpaceSource.Folder,
                AgentState.Blocked,
                tab("all", "", "t1", "t2", top = AgentState.Blocked),
            ),
            space(
                "folder::/Users/mtajchert/code/docs",
                "docs",
                "/Users/mtajchert/code/docs",
                SpaceSource.Folder,
                AgentState.Done,
                tab("all", "", "t3", "t4", "t5"),
            ),
        ),
    )

private fun stateFor(
    workspace: Workspace,
    controls: ListControls = grouped,
) = buildUiState(listOf(SampleData.overview.first()), controls, refreshing = false) { workspace }

/** The list pane as `:app` draws it beside the navigation rail: a card that holds no New button of its own. */
@Composable
private fun Dense(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPaneLayout provides PaneLayout(multiPane = true, navigationRail = true)) {
        PaneCard(PanePosition.Leading, PaneRole.List, content = content)
    }

@PreviewTest
@PreviewFormFactors
@Composable
private fun TerminalsDesktopLayoutScreenshot() =
    Shot {
        TerminalsScreen(
            stateFor(desktopWorkspace),
            TerminalsActions(),
            selected = TerminalRoute("studio", "t1"),
            nowMs = NOW,
        )
    }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsLabelsOnlyScreenshot() =
    Shot {
        TerminalsScreen(stateFor(labelsWorkspace), TerminalsActions(), nowMs = NOW)
    }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsFolderOnlyScreenshot() =
    Shot {
        TerminalsScreen(stateFor(folderWorkspace), TerminalsActions(), nowMs = NOW)
    }

@PreviewTest
@PreviewSidebar
@Composable
private fun TerminalsDenseDesktopLayoutScreenshot() =
    Shot {
        Dense {
            TerminalsScreen(
                stateFor(desktopWorkspace),
                TerminalsActions(),
                selected = TerminalRoute("studio", "t2"),
                nowMs = NOW,
            )
        }
    }

@PreviewTest
@PreviewPhone
@PreviewPhoneDark
@Composable
private fun TerminalsPriorityScreenshot() =
    Shot {
        TerminalsScreen(
            stateFor(desktopWorkspace, ListControls(showFinished = true)),
            TerminalsActions(),
            selected = TerminalRoute("studio", "t1"),
            nowMs = NOW,
        )
    }

@PreviewTest
@PreviewSidebar
@Composable
private fun TerminalsDenseFilteredScreenshot() =
    Shot {
        Dense {
            TerminalsScreen(
                stateFor(desktopWorkspace, ListControls(filter = TerminalFilter.Working, sort = TerminalSort.Grouped)),
                TerminalsActions(),
                selected = TerminalRoute("studio", "t2"),
                nowMs = NOW,
            )
        }
    }

@PreviewTest
@PreviewSidebar
@Composable
private fun TerminalsDenseLabelsOnlyScreenshot() =
    Shot { Dense { TerminalsScreen(stateFor(labelsWorkspace), TerminalsActions(), nowMs = NOW) } }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsListFilteredFinishedScreenshot() =
    Shot {
        TerminalsScreen(
            buildUiState(
                SampleData.overview,
                ListControls(filter = TerminalFilter.All, showFinished = true, sort = TerminalSort.Grouped),
                false,
            ),
            TerminalsActions(),
            nowMs = NOW,
        )
    }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsNoHostsScreenshot() =
    Shot {
        TerminalsScreen(buildUiState(emptyList(), ListControls(), false), TerminalsActions(), nowMs = NOW)
    }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsNoTerminalsScreenshot() =
    Shot {
        val ov =
            listOf(SampleData.overview.first().copy(session = SampleData.studioOnline.copy(terminals = emptyList())))
        TerminalsScreen(buildUiState(ov, ListControls(), false), TerminalsActions(), nowMs = NOW)
    }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsAllOfflineScreenshot() =
    Shot {
        val ov = SampleData.overview.drop(2)
        TerminalsScreen(buildUiState(ov, ListControls(), false), TerminalsActions(), nowMs = NOW)
    }

private val newForm =
    NewTerminalForm(
        hosts =
            listOf(
                HostChoice(HostId("studio"), "Studio Mac", listOf("rohan"), true),
                HostChoice(HostId("hetzner"), "Hetzner build box", emptyList(), false),
            ),
        hostId = HostId("studio"),
        templates =
            TemplatesState.Loaded(
                listOf(
                    TerminalTemplate("claude", "Claude Code in the project folder", "claude", null),
                    TerminalTemplate("codex", "", "codex", null),
                ),
            ),
        source = TerminalSource.Template("claude"),
        cwd = "~/code/flocket",
    )

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun NewTerminalScreenshot() = Shot { NewTerminalContent(newForm, NewTerminalCallbacks()) }

@PreviewTest
@PreviewPhone
@Composable
private fun NewTerminalErrorsScreenshot() =
    Shot {
        NewTerminalContent(
            newForm.copy(source = TerminalSource.Command, cwd = "", showErrors = true),
            NewTerminalCallbacks(),
        )
    }

private val inbox = buildUiState(SampleData.overview, ListControls(sort = TerminalSort.Inbox), refreshing = false)

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun TerminalsInboxScreenshot() = Shot { TerminalsScreen(inbox, TerminalsActions(), nowMs = NOW) }

@PreviewTest
@PreviewSidebar
@PreviewSidebarDark
@Composable
private fun TerminalsDenseInboxScreenshot() =
    Shot {
        Dense { TerminalsScreen(inbox, TerminalsActions(), selected = TerminalRoute("studio", "t1"), nowMs = NOW) }
    }
