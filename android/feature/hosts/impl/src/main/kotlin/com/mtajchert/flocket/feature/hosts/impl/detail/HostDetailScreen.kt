package com.mtajchert.flocket.feature.hosts.impl.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.designsystem.theme.flocketColors
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.MachineInfo
import com.mtajchert.flocket.core.model.MachineState
import com.mtajchert.flocket.core.ui.component.CenteredContent
import com.mtajchert.flocket.core.ui.component.ConnectionStateBadge
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.HostKeyChangedDialog
import com.mtajchert.flocket.core.ui.component.HostKeyTrustDialog
import com.mtajchert.flocket.core.ui.component.LoadingState
import com.mtajchert.flocket.core.ui.component.StaleDataBanner
import com.mtajchert.flocket.core.ui.component.rememberNowMs
import com.mtajchert.flocket.core.ui.error.UiErrorAction
import com.mtajchert.flocket.feature.hosts.impl.R

/** Everything the detail screen can ask for; all default to no-ops so previews stay one-liners. */
data class HostDetailCallbacks(
    val onBack: (() -> Unit)? = null,
    val onEdit: () -> Unit = {},
    val onHostSettings: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onErrorAction: (UiErrorAction) -> Unit = {},
    val onRequestForget: () -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onTrust: () -> Unit = {},
    val onForget: () -> Unit = {},
    val onSubmitPassword: (CharArray) -> Unit = {},
    val onDelete: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(
    state: HostDetailUiState,
    callbacks: HostDetailCallbacks,
    modifier: Modifier = Modifier,
    nowMs: Long = rememberNowMs(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.host?.label ?: stringResource(R.string.detail_title)) },
                navigationIcon = {
                    callbacks.onBack?.let {
                        IconButton(onClick = it) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription =
                                    stringResource(
                                        com.mtajchert.flocket.core.designsystem.R.string.navigate_back,
                                    ),
                            )
                        }
                    }
                },
                actions = {
                    if (state.host != null) {
                        IconButton(onClick = callbacks.onEdit) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.detail_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val host = state.host
        when {
            state.loading -> {
                LoadingState(Modifier.padding(padding))
            }

            host == null -> {
                CenteredContent(Modifier.padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.detail_not_found), Modifier.padding(24.dp))
                }
            }

            else -> {
                CenteredContent(Modifier.padding(padding), maxWidth = 720.dp) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                stringResource(R.string.host_address, host.username, host.hostname, host.port),
                                style = FlocketMono.body,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ConnectionStateBadge(state.connection, nowMs = nowMs)
                                state.core?.let { VersionChip(stringResource(R.string.detail_herdr_chip, it.version)) }
                            }
                            if (state.connection is ConnectionState.Idle) {
                                Button(onClick = callbacks.onConnect) { Text(stringResource(R.string.detail_connect)) }
                            }
                        }
                        if (state.connection !is com.mtajchert.flocket.core.model.ConnectionState.Online &&
                            state.terminalCount > 0
                        ) {
                            StaleDataBanner(state.confirmedAtMs, nowMs = nowMs)
                        }
                        state.error?.let { ErrorBanner(it, onAction = callbacks.onErrorAction) }
                        CoreSection(state)
                        if (state.machines.isNotEmpty()) MachinesSection(state.machines)
                        SettingsCard(state, callbacks.onHostSettings, callbacks.onRequestForget)
                        OutlinedButton(
                            onClick = callbacks.onRequestDelete,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    DetailDialogs(state, callbacks)
}

@Composable
private fun VersionChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FlocketCorners.Chip)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SettingsCard(
    state: HostDetailUiState,
    onHostSettings: () -> Unit,
    onForget: () -> Unit,
) {
    val none = Color.Transparent
    Card(
        Modifier.fillMaxWidth(),
        shape = FlocketCorners.CardLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListItem(
            modifier = Modifier.clickable(onClick = onHostSettings),
            colors = ListItemDefaults.colors(containerColor = none),
            leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.detail_host_settings)) },
            supportingContent = {
                Text(
                    if (state.customSettings == 0) {
                        stringResource(R.string.detail_host_settings_defaults)
                    } else {
                        pluralStringResource(
                            R.plurals.detail_host_settings_custom,
                            state.customSettings,
                            state.customSettings,
                        )
                    },
                )
            },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val pinned = state.pinnedKey
        ListItem(
            colors = ListItemDefaults.colors(containerColor = none),
            leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.detail_security)) },
            supportingContent = {
                if (pinned == null) {
                    Text(stringResource(R.string.detail_no_pinned), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "${pinned.keyType} ${pinned.fingerprint}",
                        style = FlocketMono.small,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingContent =
                pinned?.let {
                    { TextButton(onClick = onForget) { Text(stringResource(R.string.detail_forget)) } }
                },
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = FlocketCorners.CardLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun CoreSection(state: HostDetailUiState) {
    Section(stringResource(R.string.detail_core)) {
        val core = state.core
        if (core == null) {
            Text(stringResource(R.string.host_core_unknown), style = MaterialTheme.typography.bodyMedium)
        } else {
            InfoRow(stringResource(R.string.detail_core_name), core.name)
            InfoRow(stringResource(R.string.detail_core_version), core.version)
            InfoRow(stringResource(R.string.detail_core_generation), core.generation.toString())
            InfoRow(stringResource(R.string.detail_core_terminals), state.terminalCount.toString())
        }
    }
}

@Composable
private fun MachinesSection(machines: List<MachineInfo>) {
    Section(stringResource(R.string.detail_machines)) {
        machines.forEach { machine ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val (icon, label) =
                    when (machine.state) {
                        MachineState.Connected -> Icons.Outlined.CheckCircle to R.string.machine_connected
                        MachineState.Connecting -> Icons.Outlined.Sync to R.string.machine_connecting
                        MachineState.NeedsLogin -> Icons.Outlined.WarningAmber to R.string.machine_needs_login
                        MachineState.NeedsUpdate -> Icons.Outlined.WarningAmber to R.string.machine_needs_update
                        MachineState.Unreachable -> Icons.Outlined.ErrorOutline to R.string.machine_unreachable
                        MachineState.Other -> Icons.Outlined.ErrorOutline to R.string.machine_other
                    }
                val flocket = MaterialTheme.flocketColors
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint =
                        when (machine.state) {
                            MachineState.Connected -> flocket.done
                            MachineState.NeedsLogin, MachineState.NeedsUpdate -> flocket.attention
                            MachineState.Unreachable, MachineState.Other -> MaterialTheme.colorScheme.error
                            MachineState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Column(Modifier.weight(1f)) {
                    Text(machine.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            stringResource(label),
                            machine.version?.let {
                                stringResource(R.string.detail_machine_version, it)
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailDialogs(
    state: HostDetailUiState,
    callbacks: HostDetailCallbacks,
) {
    val host = state.host
    when (val dialog = state.dialog) {
        null -> {
            Unit
        }

        is HostDetailDialog.TrustKey -> {
            HostKeyTrustDialog(
                dialog.error.host,
                dialog.error.keyType,
                dialog.error.fingerprint,
                callbacks.onTrust,
                callbacks.onDismissDialog,
            )
        }

        is HostDetailDialog.KeyChanged -> {
            HostKeyChangedDialog(
                dialog.error.host,
                dialog.error.keyType,
                dialog.error.expectedFingerprint,
                dialog.error.actualFingerprint,
                onForgetPinnedKey = callbacks.onForget,
                onDismiss = callbacks.onDismissDialog,
            )
        }

        HostDetailDialog.ForgetPinned -> {
            AlertDialog(
                onDismissRequest = callbacks.onDismissDialog,
                title = { Text(stringResource(R.string.detail_forget_title)) },
                text = { Text(stringResource(R.string.detail_forget_message)) },
                confirmButton = {
                    TextButton(
                        onClick = callbacks.onForget,
                    ) { Text(stringResource(R.string.detail_forget_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = callbacks.onDismissDialog) {
                        Text(stringResource(com.mtajchert.flocket.core.ui.R.string.action_cancel))
                    }
                },
            )
        }

        HostDetailDialog.Delete -> {
            AlertDialog(
                onDismissRequest = callbacks.onDismissDialog,
                title = { Text(stringResource(R.string.detail_delete_title)) },
                text = { Text(stringResource(R.string.detail_delete_message, host?.label.orEmpty())) },
                confirmButton = {
                    TextButton(onClick = callbacks.onDelete) {
                        Text(stringResource(R.string.detail_delete_confirm), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = callbacks.onDismissDialog) {
                        Text(stringResource(com.mtajchert.flocket.core.ui.R.string.action_cancel))
                    }
                },
            )
        }

        HostDetailDialog.Password -> {
            var password by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = callbacks.onDismissDialog,
                title = { Text(stringResource(R.string.detail_password_title, host?.label.orEmpty())) },
                text = {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.detail_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { callbacks.onSubmitPassword(password.toCharArray()) },
                        enabled = password.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.detail_password_connect))
                    }
                },
                dismissButton = {
                    TextButton(onClick = callbacks.onDismissDialog) {
                        Text(stringResource(com.mtajchert.flocket.core.ui.R.string.action_cancel))
                    }
                },
            )
        }
    }
}
