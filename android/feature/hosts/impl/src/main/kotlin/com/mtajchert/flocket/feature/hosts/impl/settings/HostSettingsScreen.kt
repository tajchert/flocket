package com.mtajchert.flocket.feature.hosts.impl.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.LoadingState
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsCallbacks
import com.mtajchert.flocket.core.ui.terminalsettings.TerminalSettingsForm
import com.mtajchert.flocket.feature.hosts.impl.R

/** Everything the host settings screen can ask for; defaults are no-ops (previews, screenshots). */
class HostSettingsCallbacks(
    val onBack: () -> Unit = {},
    val onResetAll: () -> Unit = {},
    val terminal: TerminalSettingsCallbacks = TerminalSettingsCallbacks(),
    val shareHelper: ShareHelperCallbacks = ShareHelperCallbacks(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSettingsScreen(
    state: HostSettingsUiState,
    callbacks: HostSettingsCallbacks,
    modifier: Modifier = Modifier,
    shareHelper: ShareHelperUiState = ShareHelperUiState(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.host_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(com.mtajchert.flocket.core.designsystem.R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = callbacks.onResetAll,
                        enabled = state.hasOverrides,
                        modifier = Modifier.testTag("reset_all"),
                    ) { Text(stringResource(R.string.host_settings_reset_all)) }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> {
                LoadingState(Modifier.padding(padding))
            }

            state.hostLabel == null -> {
                CenteredContent(Modifier.padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.detail_not_found), Modifier.padding(24.dp))
                }
            }

            else -> {
                CenteredContent(Modifier.padding(padding), maxWidth = 720.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                        Text(
                            stringResource(R.string.host_settings_intro, state.hostLabel),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        TerminalSettingsForm(state.form, callbacks.terminal)
                        ShareHelperSection(shareHelper, callbacks.shareHelper)
                    }
                }
            }
        }
    }
}
