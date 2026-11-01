@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
        Text(stringResource(R.string.new_terminal), style = MaterialTheme.typography.titleLarge)
        if (form.hosts.isEmpty()) {
            Text(stringResource(R.string.new_terminal_no_hosts), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = callbacks.onCancel) { Text(stringResource(R.string.new_terminal_cancel)) }
            return@Column
        }
        if (form.hosts.size > 1) {
            Section(stringResource(R.string.new_terminal_host)) {
                form.hosts.forEach { host ->
                    FilterChip(
                        selected = host.id == form.hostId,
                        onClick = { callbacks.onHost(host.id) },
                        label = { Text(host.label) },
                    )
                }
            }
        }
        val machines = form.host?.machines.orEmpty()
        if (machines.isNotEmpty()) {
            Section(stringResource(R.string.new_terminal_machine)) {
                FilterChip(
                    selected = form.machine == null,
                    onClick = { callbacks.onMachine(null) },
                    label = { Text(stringResource(R.string.new_terminal_machine_local)) },
                )
                machines.forEach { machine ->
                    FilterChip(
                        selected = form.machine == machine,
                        onClick = { callbacks.onMachine(machine) },
                        label = { Text(machine) },
                    )
                }
            }
        }
        Section(stringResource(R.string.new_terminal_run)) {
            FilterChip(
                selected = form.source == TerminalSource.Shell,
                onClick = { callbacks.onSource(TerminalSource.Shell) },
                label = { Text(stringResource(R.string.new_terminal_shell)) },
            )
            (form.templates as? TemplatesState.Loaded)?.templates?.forEach { template ->
                FilterChip(
                    selected = (form.source as? TerminalSource.Template)?.name == template.name,
                    onClick = { callbacks.onSource(TerminalSource.Template(template.name)) },
                    label = { Text(template.name) },
                )
            }
            FilterChip(
                selected = form.source == TerminalSource.Command,
                onClick = { callbacks.onSource(TerminalSource.Command) },
                label = { Text(stringResource(R.string.new_terminal_command_option)) },
            )
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
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
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
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
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
                if (form.submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.new_terminal_start),
                    Modifier.padding(start = if (form.submitting) 8.dp else 0.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
