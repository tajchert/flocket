@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.feature.terminals.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.component.ErrorBanner

/** Killing is destructive and not undoable, so it always asks. */
@Composable
fun KillConfirmDialog(
    item: TerminalItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val label = item.name?.takeIf { it.isNotBlank() } ?: item.agentLabel.ifBlank { item.address }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.kill_title)) },
        text = { Text(stringResource(R.string.kill_message, label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.kill_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.new_terminal_cancel)) } },
    )
}

/** Multiline text to paste and submit. Shows queued, then the result; a `blocked` refusal asks before forcing. */
@Composable
fun SendTextDialog(
    dialog: TerminalDialog.SendText,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onForce: () -> Unit,
    onDismiss: () -> Unit,
) {
    val phase = dialog.phase
    val label = dialog.item.name?.takeIf { it.isNotBlank() } ?: dialog.item.agentLabel.ifBlank { dialog.item.address }
    val editable = phase is SendPhase.Editing || phase is SendPhase.Failed || phase is SendPhase.Blocked
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_title, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = dialog.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.send_label)) },
                    minLines = 3,
                    maxLines = 8,
                    enabled = editable,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(stringResource(R.string.send_hint)) },
                )
                when (phase) {
                    SendPhase.Queued -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.send_queued), style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    is SendPhase.Sent -> {
                        Text(
                            phase.statusWord?.let { stringResource(R.string.send_sent_status, it) }
                                ?: stringResource(R.string.send_sent),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is SendPhase.Blocked -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.send_blocked_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                stringResource(R.string.send_blocked_message, phase.message),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    SendPhase.OutcomeUnknown -> {
                        Text(
                            stringResource(R.string.send_unknown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    is SendPhase.Failed -> {
                        ErrorBanner(phase.error)
                    }

                    SendPhase.Editing -> {
                        Spacer(Modifier.height(0.dp))
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                is SendPhase.Blocked -> {
                    TextButton(onClick = onForce) {
                        Text(stringResource(R.string.send_blocked_confirm), color = MaterialTheme.colorScheme.error)
                    }
                }

                is SendPhase.Sent, SendPhase.OutcomeUnknown -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.send_close)) }
                }

                else -> {
                    TextButton(onClick = onSend, enabled = dialog.text.isNotBlank() && phase != SendPhase.Queued) {
                        Text(stringResource(R.string.send_action))
                    }
                }
            }
        },
        dismissButton = {
            if (phase !is SendPhase.Sent && phase != SendPhase.OutcomeUnknown) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.new_terminal_cancel)) }
            }
        },
    )
}
