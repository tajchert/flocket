@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.preview.PreviewFormFactors
import com.mtajchert.flocket.core.designsystem.preview.PreviewLargeFont
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalTemplate
import com.mtajchert.flocket.core.testing.SampleData
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

@Composable
private fun Shot(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) = FlocketTheme(dynamicColor = false) { Surface { content() } }

private val mixed = buildUiState(SampleData.overview, ListControls(), refreshing = false)

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun TerminalsListScreenshot() =
    Shot { TerminalsScreen(mixed, TerminalsActions(), selected = TerminalRoute("studio", "t1"), nowMs = NOW) }

@PreviewTest
@PreviewPhone
@Composable
private fun TerminalsListFilteredFinishedScreenshot() =
    Shot {
        TerminalsScreen(
            buildUiState(
                SampleData.overview,
                ListControls(filter = TerminalFilter.All, showFinished = true, attentionFirst = false),
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
