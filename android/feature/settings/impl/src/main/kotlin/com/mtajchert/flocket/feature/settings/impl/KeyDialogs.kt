@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.settings.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.component.ErrorBanner
import com.mtajchert.flocket.core.ui.component.KeySource
import com.mtajchert.flocket.core.ui.component.KeySourceFields

@Composable
fun GenerateKeyDialog(
    dialog: SettingsDialog.Generate,
    onLabel: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.generate_message), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = dialog.label,
                    onValueChange = onLabel,
                    label = { Text(stringResource(R.string.key_label)) },
                    singleLine = true,
                    isError = dialog.labelMissing,
                    supportingText =
                        if (dialog.labelMissing) {
                            (
                                {
                                    Text(
                                        stringResource(R.string.key_label_required),
                                    )
                                }
                            )
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                dialog.error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !dialog.busy) { Text(stringResource(R.string.generate_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Import from a key file by default; pasting the key text is the secondary path. */
@Composable
fun ImportKeyDialog(
    dialog: SettingsDialog.Import,
    onChange: (label: String?, pem: String?, passphrase: String?) -> Unit,
    onSource: (KeySource) -> Unit,
    onPickFile: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_message), style = MaterialTheme.typography.bodyMedium)
                KeySourceFields(
                    source = dialog.source,
                    fileName = dialog.fileName,
                    pem = dialog.pem,
                    notAKey = dialog.problem == ImportProblem.NotAPrivateKey,
                    onPickFile = onPickFile,
                    onPemChange = { onChange(null, it, null) },
                    onSourceChange = onSource,
                    enabled = !dialog.busy,
                )
                OutlinedTextField(
                    value = dialog.label,
                    onValueChange = { onChange(it, null, null) },
                    label = { Text(stringResource(R.string.key_label)) },
                    singleLine = true,
                    isError = dialog.problem == ImportProblem.LabelMissing,
                    supportingText =
                        if (dialog.problem == ImportProblem.LabelMissing) {
                            ({ Text(stringResource(R.string.key_label_required)) })
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dialog.passphrase,
                    onValueChange = { onChange(null, null, it) },
                    label = { Text(stringResource(R.string.import_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                dialog.error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !dialog.busy && dialog.pem.isNotBlank()) {
                Text(stringResource(R.string.import_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Deleting is final, and hosts that sign in with the key will stop connecting: say so before asking. */
@Composable
fun DeleteKeyDialog(
    item: KeyItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title, item.key.label)) },
        text = {
            Text(
                if (item.usedBy.isEmpty()) {
                    stringResource(R.string.delete_message_unused)
                } else {
                    pluralStringResource(
                        R.plurals.delete_message_used,
                        item.usedBy.size,
                        item.usedBy.size,
                        item.usedBy.joinToString(),
                    )
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) { Text(stringResource(R.string.key_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
