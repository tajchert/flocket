@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketCorners
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.component.ErrorBanner

@Immutable
class NewTerminalCallbacks(
    val onHost: (HostId) -> Unit = {},
    val onMachine: (String?) -> Unit = {},
    val onSource: (TerminalSource) -> Unit = {},
    val onCommand: (String) -> Unit = {},
    val onCwd: (String) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onKeep: (Boolean) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTerminalSheet(
    form: NewTerminalForm,
    callbacks: NewTerminalCallbacks,
) {
    ModalBottomSheet(
        onDismissRequest = callbacks.onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        NewTerminalContent(form, callbacks)
    }
}

/** The form itself, separate from the sheet chrome so it can be tested and screenshotted directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewTerminalContent(
    form: NewTerminalForm,
    callbacks: NewTerminalCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.new_terminal),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp),
        )
        if (form.hosts.isEmpty()) {
            Text(stringResource(R.string.new_terminal_no_hosts), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = callbacks.onCancel) { Text(stringResource(R.string.new_terminal_cancel)) }
            return@Column
        }
        if (form.hosts.size > 1) {
            Section(stringResource(R.string.new_terminal_host)) {
                form.hosts.forEach { host ->
                    SheetChip(
                        selected = host.id == form.hostId,
                        onClick = { callbacks.onHost(host.id) },
                        label = host.label,
                    )
                }
            }
        }
        val machines = form.host?.machines.orEmpty()
        if (machines.isNotEmpty()) {
            Section(stringResource(R.string.new_terminal_machine)) {
                SheetChip(
                    selected = form.machine == null,
                    onClick = { callbacks.onMachine(null) },
                    label = stringResource(R.string.new_terminal_machine_local),
                )
                machines.forEach { machine ->
                    SheetChip(
                        selected = form.machine == machine,
                        onClick = { callbacks.onMachine(machine) },
                        label = machine,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.new_terminal_run))
            val templates = (form.templates as? TemplatesState.Loaded)?.templates.orEmpty()
            val tiles =
                buildList {
                    add(
                        SourceTile(
                            stringResource(R.string.new_terminal_shell),
                            Icons.Outlined.Terminal,
                            form.source == TerminalSource.Shell,
                        ) { callbacks.onSource(TerminalSource.Shell) },
                    )
                    templates.forEach { template ->
                        add(
                            SourceTile(
                                template.name,
                                Icons.Outlined.AutoAwesome,
                                (form.source as? TerminalSource.Template)?.name == template.name,
                            ) { callbacks.onSource(TerminalSource.Template(template.name)) },
                        )
                    }
                    add(
                        SourceTile(
                            stringResource(R.string.new_terminal_command_option),
                            Icons.Outlined.Code,
                            form.source == TerminalSource.Command,
                        ) { callbacks.onSource(TerminalSource.Command) },
                    )
                }
            tiles.chunked(3).forEach { rowTiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTiles.forEach { SourceTileView(it, Modifier.weight(1f)) }
                    repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        when (form.templates) {
            TemplatesState.Loading -> {
                Text(
                    stringResource(R.string.new_terminal_templates_loading),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            TemplatesState.Failed -> {
                Text(
                    stringResource(R.string.new_terminal_templates_failed),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                Unit
            }
        }
        val selectedTemplate =
            (form.source as? TerminalSource.Template)?.let { src ->
                (form.templates as? TemplatesState.Loaded)?.templates?.firstOrNull { it.name == src.name }
            }
        selectedTemplate?.let {
            Text(
                it.description.ifBlank { it.command },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (form.source == TerminalSource.Command) {
            val error = form.showErrors && form.commandMissing
            OutlinedTextField(
                value = form.command,
                onValueChange = callbacks.onCommand,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.new_terminal_command)) },
                isError = error,
                supportingText =
                    if (error) {
                        (
                            {
                                Text(
                                    stringResource(R.string.new_terminal_command_required),
                                )
                            }
                        )
                    } else {
                        null
                    },
                textStyle = FlocketMono.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
        }
        val cwdError = form.showErrors && form.cwdMissing
        OutlinedTextField(
            value = form.cwd,
            onValueChange = callbacks.onCwd,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.new_terminal_cwd)) },
            singleLine = true,
            isError = cwdError,
            supportingText = {
                Text(
                    stringResource(
                        if (cwdError) R.string.new_terminal_cwd_required else R.string.new_terminal_cwd_hint,
                    ),
                )
            },
            textStyle = FlocketMono.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = form.name,
            onValueChange = callbacks.onName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.new_terminal_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.new_terminal_keep), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.new_terminal_keep_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = form.keep, onCheckedChange = callbacks.onKeep)
        }
        form.error?.let { ErrorBanner(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = callbacks.onCancel) { Text(stringResource(R.string.new_terminal_cancel)) }
            Button(onClick = callbacks.onSubmit, enabled = !form.submitting) {
                if (form.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                }
                Text(stringResource(R.string.new_terminal_start), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

/** Chip-style option: selected = secondary container with a check, unselected = outlined (8dp radius, 48dp target). */
@Composable
private fun SheetChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = FlocketCorners.Chip,
            color = if (selected) scheme.secondaryContainer else Color.Transparent,
            contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Row(
                Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private class SourceTile(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SourceTileView(
    tile: SourceTile,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = tile.selected, role = Role.RadioButton, onClick = tile.onClick),
        shape = FlocketCorners.Card,
        color = if (tile.selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (tile.selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = if (tile.selected) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            Modifier.heightIn(min = 72.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(if (tile.selected) Icons.Outlined.Check else tile.icon, contentDescription = null)
            Text(
                tile.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
