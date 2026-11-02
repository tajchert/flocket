package com.mtajchert.flocket.core.ui.hostform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.ui.R

/**
 * The add/edit host fields, shared by onboarding and the hosts feature. Stateless: the caller owns
 * [state] and [errors] (empty until the user tried to save).
 */
@Composable
fun HostForm(
    state: HostFormState,
    errors: Map<HostFormField, HostFormError>,
    keys: List<DeviceKey>,
    onChange: (HostFormState) -> Unit,
    onImportKey: () -> Unit,
    onCreateKey: () -> Unit,
    modifier: Modifier = Modifier,
    isCreatingKey: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(
            value = state.label,
            label = stringResource(R.string.form_label),
            error = errors[HostFormField.Label],
            placeholder = stringResource(R.string.form_label_hint),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            onValueChange = { onChange(state.copy(label = it)) },
        )
        FormField(
            value = state.hostname,
            label = stringResource(R.string.form_hostname),
            error = errors[HostFormField.Hostname],
            keyboard =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            mono = true,
            onValueChange = { onChange(state.copy(hostname = it)) },
        )
        FormField(
            value = state.username,
            label = stringResource(R.string.form_username),
            error = errors[HostFormField.Username],
            keyboard =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next,
                ),
            mono = true,
            onValueChange = { onChange(state.copy(username = it)) },
        )

        Text(
            stringResource(R.string.form_auth),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AuthKind.entries.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = state.authKind == kind,
                    onClick = { onChange(state.copy(authKind = kind)) },
                    shape = SegmentedButtonDefaults.itemShape(index, AuthKind.entries.size),
                ) {
                    Text(
                        stringResource(
                            if (kind ==
                                AuthKind.Key
                            ) {
                                R.string.form_auth_key
                            } else {
                                R.string.form_auth_password
                            },
                        ),
                    )
                }
            }
        }
        if (state.authKind == AuthKind.Password) {
            Text(
                stringResource(R.string.form_password_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            KeyPicker(state, errors[HostFormField.Key], keys, onChange, onImportKey, onCreateKey, isCreatingKey)
        }

        TextButton(onClick = { onChange(state.copy(advancedExpanded = !state.advancedExpanded)) }) {
            Text(stringResource(R.string.form_advanced))
            Icon(
                if (state.advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        AnimatedVisibility(state.advancedExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField(
                    value = state.port,
                    label = stringResource(R.string.form_port),
                    error = errors[HostFormField.Port],
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    mono = true,
                    onValueChange = { onChange(state.copy(port = it.filter(Char::isDigit))) },
                )
                FormField(
                    value = state.hcPath,
                    label = stringResource(R.string.form_hc_path),
                    error = errors[HostFormField.HcPath],
                    placeholder = stringResource(R.string.form_hc_path_hint),
                    keyboard =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    mono = true,
                    onValueChange = { onChange(state.copy(hcPath = it)) },
                )
                FormField(
                    value = state.session,
                    label = stringResource(R.string.form_session),
                    error = errors[HostFormField.Session],
                    placeholder = stringResource(R.string.form_session_hint),
                    keyboard =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done,
                        ),
                    mono = true,
                    onValueChange = { onChange(state.copy(session = it)) },
                )
            }
        }
    }
}

@Composable
private fun FormField(
    value: String,
    label: String,
    error: HostFormError?,
    keyboard: KeyboardOptions,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    mono: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.message)) } },
        singleLine = true,
        textStyle = if (mono) FlocketMono.body else LocalTextStyle.current,
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPicker(
    state: HostFormState,
    error: HostFormError?,
    keys: List<DeviceKey>,
    onChange: (HostFormState) -> Unit,
    onImportKey: () -> Unit,
    onCreateKey: () -> Unit,
    isCreatingKey: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (keys.isEmpty()) {
            Text(stringResource(R.string.form_no_keys), style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(Modifier.selectableGroup()) {
                keys.forEach { key ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(selected = state.keyAlias == key.alias, role = Role.RadioButton, onClick = {
                                onChange(state.copy(keyAlias = key.alias))
                            }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.keyAlias == key.alias,
                            onClick = null,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column {
                            Text(key.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.form_key_option, key.keyType, key.fingerprint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (error != null) {
            Text(
                stringResource(error.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // The key people already use is the natural path, so importing comes first.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = onImportKey,
                enabled = !isCreatingKey,
            ) { Text(stringResource(R.string.form_import_key)) }
            OutlinedButton(
                onClick = onCreateKey,
                enabled = !isCreatingKey,
            ) { Text(stringResource(R.string.form_create_key)) }
        }
    }
}
