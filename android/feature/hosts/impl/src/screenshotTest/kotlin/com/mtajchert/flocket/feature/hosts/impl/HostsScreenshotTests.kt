package com.mtajchert.flocket.feature.hosts.impl

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.designsystem.preview.PreviewFormFactors
import com.mtajchert.flocket.core.designsystem.preview.PreviewLargeFont
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.ClipboardWritePolicy
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.HostSetting
import com.mtajchert.flocket.core.model.TerminalColorScheme
import com.mtajchert.flocket.core.model.TerminalPreferences
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.hostform.HostFormState
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsFormState
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailCallbacks
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailDialog
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailScreen
import com.mtajchert.flocket.feature.hosts.impl.detail.HostDetailUiState
import com.mtajchert.flocket.feature.hosts.impl.edit.HostEditScreen
import com.mtajchert.flocket.feature.hosts.impl.edit.HostEditUiState
import com.mtajchert.flocket.feature.hosts.impl.list.HostsScreen
import com.mtajchert.flocket.feature.hosts.impl.list.HostsUiState
import com.mtajchert.flocket.feature.hosts.impl.list.toItem
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsCallbacks
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsScreen
import com.mtajchert.flocket.feature.hosts.impl.settings.HostSettingsUiState

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

@Composable
private fun Shot(content: @Composable () -> Unit) = FlocketTheme(dynamicColor = false) { Surface { content() } }

private val populated = HostsUiState(SampleData.overview.map(::toItem))

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun HostsListScreenshot() =
    Shot {
        HostsScreen(
            populated,
            onHostClick = {},
            onAddHost = {},
            onTour = {},
            nowMs = NOW,
            selectedHostId = HostId("studio"),
        )
    }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun HostsEmptyScreenshot() =
    Shot {
        HostsScreen(HostsUiState(), onHostClick = {}, onAddHost = {}, onTour = {}, nowMs = NOW)
    }

private val editState =
    HostEditUiState(
        isNew = true,
        loading = false,
        form =
            HostFormState(
                label = "Studio Mac",
                hostname = "studio.tail1234.ts.net",
                username = "mtajchert",
                keyAlias = "key-1",
            ),
        keys = listOf(SampleData.deviceKey),
    )

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun HostEditScreenshot() =
    Shot {
        HostEditScreen(editState, onFormChange = {}, onCreateKey = {}, onSave = {}, onClose = {})
    }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun HostEditValidationScreenshot() {
    val form = HostFormState(hostname = "my host", port = "0", advancedExpanded = true, session = "-bad")
    Shot {
        HostEditScreen(editState.copy(form = form, errors = form.validate(), keys = emptyList()), onFormChange = {
        }, onCreateKey = {}, onSave = {}, onClose = {})
    }
}

private fun detail(index: Int): HostDetailUiState {
    val o = SampleData.overview[index]
    val connection = o.session.connection
    val error =
        (connection as? com.mtajchert.flocket.core.model.ConnectionState.NeedsAttention)?.error
            ?: (connection as? com.mtajchert.flocket.core.model.ConnectionState.Reconnecting)?.lastError
    return HostDetailUiState(
        loading = false,
        host = o.host,
        connection = connection,
        error = error?.toUiError(),
        core = o.session.core,
        machines = o.session.machines,
        terminalCount = o.session.terminals.size,
        confirmedAtMs = o.session.confirmedAtMs,
        pinnedKey = if (index == 0) SampleData.pinnedKey else null,
    )
}

@PreviewTest
@PreviewFormFactors
@PreviewLargeFont
@PreviewPhoneDark
@Composable
private fun HostDetailOnlineScreenshot() =
    Shot {
        HostDetailScreen(detail(0), HostDetailCallbacks(onBack = {}), nowMs = NOW)
    }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun HostDetailStaleScreenshot() =
    Shot {
        HostDetailScreen(detail(1), HostDetailCallbacks(onBack = {}), nowMs = NOW)
    }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun HostDetailNeedsTrustScreenshot() =
    Shot {
        HostDetailScreen(detail(2), HostDetailCallbacks(onBack = {}), nowMs = NOW)
    }

// ---- layered settings ---------------------------------------------------------------------------

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun HostsListCustomBadgeScreenshot() =
    Shot {
        HostsScreen(
            HostsUiState(SampleData.overview.map { toItem(it, customized = it.host.id == HostId("studio")) }),
            onHostClick = {},
            onAddHost = {},
            onTour = {},
            nowMs = NOW,
        )
    }

private val appDefaults = TerminalPreferences()
private val hostEffective =
    appDefaults.copy(
        fontSizeSp = 16f,
        colorScheme = TerminalColorScheme.SolarizedDark,
        clipboardWritePolicy = ClipboardWritePolicy.Allow,
        defaultWorkingDirectory = "~/code",
    )
private val customHostSettings =
    HostSettingsUiState(
        loading = false,
        hostLabel = "Studio Mac",
        form =
            TerminalSettingsFormState(
                hostEffective,
                appDefaults,
                setOf(
                    HostSetting.FontSize,
                    HostSetting.ColorScheme,
                    HostSetting.Clipboard,
                    HostSetting.WorkingDirectory,
                ),
            ),
    )
private val defaultHostSettings =
    HostSettingsUiState(
        loading = false,
        hostLabel = "Studio Mac",
        form = TerminalSettingsFormState(appDefaults, appDefaults, emptySet()),
    )

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 2300, apiLevel = 36)
private annotation class PreviewTallPhone

@Preview(
    name = "Phone dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 2300,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
private annotation class PreviewTallPhoneDark

@Preview(
    name = "Large font",
    showBackground = true,
    widthDp = 412,
    heightDp = 3700,
    fontScale = 1.5f,
    apiLevel = 36,
)
private annotation class PreviewTallPhoneLargeFont

@Preview(name = "Tablet", showBackground = true, widthDp = 1280, heightDp = 1800, apiLevel = 36)
private annotation class PreviewTallTablet

@PreviewTest
@PreviewTallPhone
@PreviewTallPhoneDark
@PreviewTallPhoneLargeFont
@PreviewTallTablet
@Composable
private fun HostSettingsCustomScreenshot() = Shot { HostSettingsScreen(customHostSettings, HostSettingsCallbacks()) }

@PreviewTest
@PreviewTallPhone
@Composable
private fun HostSettingsDefaultsScreenshot() = Shot { HostSettingsScreen(defaultHostSettings, HostSettingsCallbacks()) }

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
private fun HostDetailCustomSettingsScreenshot() =
    Shot {
        HostDetailScreen(
            HostDetailUiState(
                loading = false,
                host = SampleData.studio,
                connection = ConnectionState.Idle,
                customSettings = 4,
            ),
            HostDetailCallbacks(),
            nowMs = NOW,
        )
    }
