@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.settings.impl

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.preview.PreviewFormFactors
import com.mtajchert.flocket.core.designsystem.preview.PreviewLargeFont
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.BellMode
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.testing.SampleData

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewPhoneDark

@Composable
private fun Shot(content: @Composable () -> Unit) =
    FlocketTheme(dynamicColor = false) { Surface(Modifier.fillMaxSize()) { content() } }

private val keyItem = KeyItem(SampleData.deviceKey, listOf("Studio Mac", "Hetzner build box"))
private val state =
    SettingsUiState(
        loaded = true,
        keys = listOf(keyItem, KeyItem(SampleData.deviceKey.copy(alias = "old-laptop"), emptyList())),
    )
private val info = AppInfo("0.1.0", 1)

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun SettingsScreenshot() = Shot { SettingsScreen(state, info, SettingsCallbacks()) }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun SettingsNoKeysScreenshot() =
    Shot {
        SettingsScreen(SettingsUiState(loaded = true), info, SettingsCallbacks(), dynamicColorAvailable = false)
    }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun ConnectionSectionScreenshot() =
    Shot {
        Column {
            ConnectionSection(state, SettingsCallbacks())
            ConnectionSection(
                state.copy(preferences = state.preferences.copy(keepConnectedInBackground = false)),
                SettingsCallbacks(),
            )
        }
    }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun KeysSectionScreenshot() = Shot { Column { KeysSection(state.keys, SettingsCallbacks()) } }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun LicensesScreenshot() =
    Shot {
        LicensesScreen(
            parseNotices(
                java.io
                    .File("src/main/res/raw/third_party_notices.md")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?: "| Library | License | Use |\n|---|---|---|\n| SSHJ | Apache-2.0 | SSH |",
            ),
            onBack = {},
        )
    }

private val customDefaults =
    state.copy(
        preferences =
            state.preferences.copy(
                terminal =
                    TerminalPreferences(
                        fontSizeSp = 15f,
                        colorScheme = TerminalColorScheme.SolarizedDark,
                        showExtraKeys = true,
                        keepScreenOn = false,
                        clipboardWritePolicy = ClipboardWritePolicy.Deny,
                        bell = BellMode.Off,
                        autoConnect = false,
                        defaultWorkingDirectory = "~/code",
                    ),
            ),
    )

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 1750, apiLevel = 36)
private annotation class PreviewTall

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 1750,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewTallDark

@Preview(
    name = "Large font",
    showBackground = true,
    widthDp = 412,
    heightDp = 2900,
    fontScale = 1.5f,
    apiLevel = 36,
)
private annotation class PreviewTallLargeFont

@PreviewTest
@PreviewTall
@PreviewTallDark
@PreviewTallLargeFont
@Composable
private fun TerminalDefaultsScreenshot() = Shot { Column { TerminalSection(state, SettingsCallbacks()) } }

@PreviewTest
@PreviewTall
@Composable
private fun TerminalDefaultsCustomScreenshot() =
    Shot {
        Column { TerminalSection(customDefaults, SettingsCallbacks()) }
    }
