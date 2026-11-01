package com.mtajchert.flocket.feature.hosts.impl.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.error.asString
import com.mtajchert.flocket.feature.hosts.impl.R
import kotlinx.coroutines.launch

/** What the share-helper section can ask for; defaults are no-ops (previews, screenshots). */
class ShareHelperCallbacks(
    val onConnect: () -> Unit = {},
    val onInstall: (machine: String?) -> Unit = {},
    val onRecheck: () -> Unit = {},
)

/** Install `flocket-share` per machine and copy the agent instruction. */
@Composable
fun ShareHelperSection(
    state: ShareHelperUiState,
    callbacks: ShareHelperCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.share_helper_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.share_helper_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.online) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.share_helper_connect_hint), Modifier.weight(1f))
                OutlinedButton(onClick = callbacks.onConnect, modifier = Modifier.testTag("share_helper_connect")) {
                    Text(stringResource(R.string.share_helper_connect))
                }
            }
        } else {
            state.rows.forEach { MachineRow(it, callbacks) }
            TextButton(onClick = callbacks.onRecheck) { Text(stringResource(R.string.share_helper_recheck)) }
        }
        if (state.instruction.isNotEmpty()) InstructionCard(state.instruction)
    }
}

@Composable
private fun MachineRow(
    row: HelperRow,
    callbacks: ShareHelperCallbacks,
) {
    val name = row.machine ?: stringResource(R.string.share_helper_this_host)
    val status = row.status
    val tag = row.machine ?: "local"
    Column(Modifier.testTag("share_helper_row_$tag"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                val summary =
                    when (status) {
                        HelperStatus.Checking -> stringResource(R.string.share_helper_checking)
                        is HelperStatus.Installed -> stringResource(R.string.share_helper_installed, status.path)
                        HelperStatus.NotInstalled -> stringResource(R.string.share_helper_not_installed)
                        HelperStatus.Installing -> stringResource(R.string.share_helper_installing)
                        HelperStatus.MayNotBeInstalled -> stringResource(R.string.share_helper_may_not_installed)
                        is HelperStatus.Unknown -> status.error.message.asString()
                    }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val canInstall = status !is HelperStatus.Checking && status !is HelperStatus.Installing
            if (canInstall) {
                val installed = status is HelperStatus.Installed
                val label = if (installed) R.string.share_helper_reinstall else R.string.share_helper_install
                val onClick = { callbacks.onInstall(row.machine) }
                if (installed) {
                    OutlinedButton(onClick) { Text(stringResource(label)) }
                } else {
                    Button(onClick, modifier = Modifier.testTag("share_helper_install_$tag")) {
                        Text(stringResource(label))
                    }
                }
            }
        }
        if (status is HelperStatus.Installed && status.onPath == false) {
            Text(
                stringResource(R.string.share_helper_path_warning, status.path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("share_helper_path_warning"),
            )
        }
    }
}

@Composable
private fun InstructionCard(instruction: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.share_helper_instruction_title), style = MaterialTheme.typography.titleSmall)
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(
                instruction,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Flocket", instruction)))
                    copied = true
                }
            },
            modifier = Modifier.testTag("share_helper_copy"),
        ) { Text(stringResource(if (copied) R.string.share_helper_copied else R.string.share_helper_copy)) }
    }
}
