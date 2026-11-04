package com.mtajchert.flocket.feature.terminal.impl.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.feature.terminal.impl.R

@Composable
fun ApkInstallDialog(
    info: ApkInfo,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apk_install_title, info.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.versionName?.let {
                    Text(stringResource(R.string.apk_install_version, it), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    info.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onInstall) { Text(stringResource(R.string.apk_install_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.apk_install_not_now)) } },
    )
}

@Composable
fun ApkPermissionDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apk_permission_title)) },
        text = { Text(stringResource(R.string.apk_permission_message)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.apk_permission_open_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.apk_install_not_now)) } },
    )
}
