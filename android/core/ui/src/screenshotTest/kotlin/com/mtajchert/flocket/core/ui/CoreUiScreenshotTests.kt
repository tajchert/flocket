package com.mtajchert.flocket.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.designsystem.preview.PreviewComponentThemes
import com.mtajchert.flocket.core.designsystem.theme.FlocketTheme
import com.mtajchert.flocket.core.model.AgentState
import com.mtajchert.flocket.core.model.AgentStatus
import com.mtajchert.flocket.core.model.BlockKind
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.testing.SampleData
import com.mtajchert.flocket.core.ui.component.AgentStatusChip
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.EmptyState
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.ErrorState
import com.mtajchert.flocket.core.ui.component.ImportKeyDialog
import com.mtajchert.flocket.core.ui.component.ImportKeyDialogState
import com.mtajchert.flocket.core.ui.component.LoadingState
import com.mtajchert.flocket.core.ui.component.PublicKeyCard
import com.mtajchert.flocket.core.ui.component.RenameDialog
import com.mtajchert.flocket.core.ui.component.StaleDataBanner
import com.mtajchert.flocket.core.ui.error.toUiError
import com.mtajchert.flocket.core.ui.hostform.HostForm
import com.mtajchert.flocket.core.ui.hostform.HostFormState

private const val NOW = SampleData.NOW_MS

@Composable
private fun Shot(content: @Composable () -> Unit) {
    FlocketTheme(dynamicColor = false) {
        Surface { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

private fun reported(
    state: AgentState,
    kind: BlockKind? = null,
    progress: Int? = null,
    raw: String = state.name.lowercase(),
) = AgentStatus.Reported(state, raw, kind, progress)

@PreviewTest
@PreviewComponentThemes
@Composable
private fun ConnectionBadgesScreenshot() =
    Shot {
        ConnectionStateBadge(ConnectionState.Idle, nowMs = NOW)
        ConnectionStateBadge(ConnectionState.Connecting(1), nowMs = NOW)
        ConnectionStateBadge(ConnectionState.Online(SampleData.coreInfo), nowMs = NOW)
        ConnectionStateBadge(ConnectionState.Reconnecting(2, AppError.ConnectionLost, NOW + 12_000), nowMs = NOW)
        ConnectionStateBadge(ConnectionState.NeedsAttention(SampleData.hostKeyUnknown), nowMs = NOW)
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun AgentStatusChipsScreenshot() =
    Shot {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentStatusChip(AgentStatus.Unknown)
            AgentStatusChip(reported(AgentState.Idle))
            AgentStatusChip(reported(AgentState.Working))
            AgentStatusChip(reported(AgentState.Working, progress = 62))
            AgentStatusChip(reported(AgentState.Done))
            AgentStatusChip(reported(AgentState.Blocked, BlockKind.Permission))
            AgentStatusChip(reported(AgentState.Blocked, BlockKind.Question))
            AgentStatusChip(reported(AgentState.Blocked, BlockKind.Auth))
            AgentStatusChip(reported(AgentState.Error))
            AgentStatusChip(reported(AgentState.Other, raw = "compacting"))
        }
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun StateComponentsScreenshot() =
    Shot {
        StaleDataBanner(NOW - 600_000, nowMs = NOW)
        StaleDataBanner(null, nowMs = NOW)
        ErrorBanner(AppError.CoreNotFound(listOf("/usr/local/bin/hc"), "hc: command not found").toUiError())
        ErrorBanner(SampleData.hostKeyUnknown.toUiError())
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun FullAreaStatesScreenshot() =
    Shot {
        Surface(Modifier.height(240.dp)) {
            EmptyState(
                Icons.Outlined.Dns,
                "No hosts yet",
                "Add a machine running herdr-core.",
                actionLabel = "Add host",
            )
        }
        Surface(
            Modifier.height(260.dp),
        ) { ErrorState(AppError.Unreachable("studio.local", "Connection refused").toUiError()) }
        Surface(Modifier.height(80.dp)) { LoadingState() }
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun PublicKeyCardScreenshot() = Shot { PublicKeyCard(SampleData.deviceKey) }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun HostFormScreenshot() =
    Shot {
        HostForm(
            state =
                HostFormState(
                    label = "",
                    hostname = "bad host",
                    port = "99999",
                    advancedExpanded = true,
                    session = "Bad Name",
                    hcPath = "hc",
                ),
            errors =
                HostFormState(
                    label = "",
                    hostname = "bad host",
                    port = "99999",
                    advancedExpanded = true,
                    session = "Bad Name",
                    hcPath = "hc",
                ).validate(),
            keys = listOf(SampleData.deviceKey),
            onChange = {},
            onImportKey = {},
            onCreateKey = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun HostFormNoKeysScreenshot() =
    Shot {
        HostForm(
            state = HostFormState(label = "Studio", hostname = "studio.local", username = "me"),
            errors = emptyMap(),
            keys = emptyList(),
            onChange = {},
            onImportKey = {},
            onCreateKey = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun ImportKeyDialogScreenshot() =
    Shot {
        ImportKeyDialog(
            state =
                ImportKeyDialogState(
                    fileName = "android_mini_ed25519",
                    label = "android_mini_ed25519",
                    pem = "-----BEGIN OPENSSH PRIVATE KEY-----",
                ),
            onChange = { _, _, _ -> },
            onSource = {},
            onPickFile = {},
            onConfirm = {},
            onDismiss = {},
        )
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun RenameTabDialogScreenshot() =
    Shot {
        RenameDialog(
            isTab = true,
            text = "agents",
            placeholder = "2",
            onTextChange = {},
            onSave = {},
            onDismiss = {},
        )
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun RenameSpaceDialogEmptyScreenshot() =
    Shot {
        RenameDialog(
            isTab = false,
            text = "",
            placeholder = "flocket",
            onTextChange = {},
            onSave = {},
            onDismiss = {},
        )
    }

@Composable
private fun SchemePreviews(schemes: List<com.mtajchert.flocket.core.model.TerminalColorScheme>) =
    Shot {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            schemes.forEach { scheme ->
                androidx.compose.material3.Text(
                    scheme.name,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                )
                com.mtajchert.flocket.core.ui.terminalsettings
                    .TerminalPreview(fontSizeSp = 12f, scheme = scheme)
            }
        }
    }

@PreviewTest
@PreviewComponentThemes
@Composable
private fun TerminalSchemePreviewsAScreenshot() =
    SchemePreviews(
        com.mtajchert.flocket.core.model.TerminalColorScheme.entries
            .take(3),
    )

@PreviewTest
@PreviewComponentThemes
@Composable
private fun TerminalSchemePreviewsBScreenshot() =
    SchemePreviews(
        com.mtajchert.flocket.core.model.TerminalColorScheme.entries
            .drop(3),
    )
