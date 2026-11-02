@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.ui.R

/** `SHA256:abc…` -> `SHA256:` followed by groups of four characters, for reading aloud and comparing. */
fun groupFingerprint(fingerprint: String): String {
    val prefix = fingerprint.substringBefore(':', "")
    val body = if (':' in fingerprint) fingerprint.substringAfter(':') else fingerprint
    val grouped = body.chunked(4).joinToString(" ")
    return if (prefix.isEmpty()) grouped else "$prefix:\n$grouped"
}

@Composable
private fun LabelledMono(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasise: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color =
                if (emphasise) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (emphasise) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Text(
                value,
                style = FlocketMono.body.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp),
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/** First contact: the user decides whether to pin this host key. Offers "Trust and connect" and "Cancel". */
@Composable
fun HostKeyTrustDialog(
    host: String,
    keyType: String,
    fingerprint: String,
    onTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Security, contentDescription = null) },
        title = { Text(stringResource(R.string.trust_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.trust_intro, host), style = MaterialTheme.typography.bodyMedium)
                LabelledMono(stringResource(R.string.trust_key_type), keyType)
                LabelledMono(stringResource(R.string.trust_fingerprint), groupFingerprint(fingerprint))
                Text(
                    stringResource(R.string.trust_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.trust_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The host presented a different key than the pinned one. This dialog never offers to trust the new key.
 * It is not dismissed by tapping outside; the only way to proceed is "Forget pinned key…" and a second
 * confirmation that explains the consequence.
 */
@Composable
fun HostKeyChangedDialog(
    host: String,
    keyType: String,
    expectedFingerprint: String,
    actualFingerprint: String,
    onForgetPinnedKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.changed_forget_title)) },
            text = { Text(stringResource(R.string.changed_forget_message, host)) },
            confirmButton = {
                TextButton(onClick = onForgetPinnedKey) {
                    Text(stringResource(R.string.changed_forget_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirming = false },
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            icon = { Icon(Icons.Outlined.GppMaybe, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.changed_title), color = MaterialTheme.colorScheme.error) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.changed_intro, host), style = MaterialTheme.typography.bodyMedium)
                    LabelledMono(
                        stringResource(R.string.changed_expected),
                        "$keyType\n${groupFingerprint(expectedFingerprint)}",
                    )
                    LabelledMono(
                        stringResource(R.string.changed_actual),
                        "$keyType\n${groupFingerprint(actualFingerprint)}",
                        emphasise = true,
                    )
                    Text(
                        stringResource(R.string.changed_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.changed_keep_blocked)) } },
            dismissButton = {
                TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.action_forget_pinned_key)) }
            },
        )
    }
}
