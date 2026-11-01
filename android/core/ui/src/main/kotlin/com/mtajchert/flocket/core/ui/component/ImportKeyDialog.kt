package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.R

/** Import from a key file by default; pasting the key text is the secondary path. */
@Composable
fun ImportKeyDialog(
    state: ImportKeyDialogState,
    onChange: (label: String?, pem: String?, passphrase: String?) -> Unit,
    onSource: (KeySource) -> Unit,
    onPickFile: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.key_import_message), style = MaterialTheme.typography.bodyMedium)
                KeySourceFields(
                    source = state.source,
                    fileName = state.fileName,
                    pem = state.pem,
                    notAKey = state.problem == ImportKeyProblem.NotAPrivateKey,
                    onPickFile = onPickFile,
                    onPemChange = { onChange(null, it, null) },
                    onSourceChange = onSource,
                    enabled = !state.busy,
                )
                OutlinedTextField(
                    value = state.label,
                    onValueChange = { onChange(it, null, null) },
                    label = { Text(stringResource(R.string.key_label)) },
                    singleLine = true,
                    isError = state.problem == ImportKeyProblem.LabelMissing,
                    supportingText =
                        if (state.problem == ImportKeyProblem.LabelMissing) {
                            ({ Text(stringResource(R.string.key_label_required)) })
                        } else {
                            null
                        },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = { onChange(null, null, it) },
                    label = { Text(stringResource(R.string.key_import_dialog_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.busy && state.pem.isNotBlank()) {
                Text(stringResource(R.string.key_import_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
