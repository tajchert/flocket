package com.mtajchert.flocket.core.ui.component

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtajchert.flocket.core.designsystem.theme.FlocketMono
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.ui.R
import kotlinx.coroutines.launch

/** The one-liner that authorizes this device on the host. Single quotes in [publicKey] are escaped. */
fun authorizedKeysCommand(publicKey: String): String =
    "echo '${publicKey.trim().replace("'", "'\\''")}' >> ~/.ssh/authorized_keys"

/**
 * Shows a device public key with copy, share and the `authorized_keys` one-liner in a terminal block.
 * Pass [showTitle] = false when the surrounding page already carries the heading.
 */
@Composable
fun PublicKeyCard(
    key: DeviceKey,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable { mutableStateOf<String?>(null) }
    val command = authorizedKeysCommand(key.publicKeyOpenSsh)

    fun copy(
        id: String,
        text: String,
    ) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Flocket", text)))
            copied = id
        }
    }

    val chooserTitle = stringResource(R.string.pubkey_share_chooser)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showTitle) Text(stringResource(R.string.pubkey_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pubkey_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TerminalCommandBlock(command, Modifier.fillMaxWidth())
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { copy("command", command) }, modifier = Modifier.heightIn(min = 40.dp)) {
                CopyIcon(copied == "command")
                Text(
                    stringResource(if (copied == "command") R.string.pubkey_copied else R.string.pubkey_copy_command),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(
                onClick = {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, key.publicKeyOpenSsh)
                    context.startActivity(Intent.createChooser(send, chooserTitle))
                },
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Text(stringResource(R.string.pubkey_share), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pubkey_fingerprint_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { copy("key", key.publicKeyOpenSsh) }, modifier = Modifier.size(32.dp)) {
                        CopyIcon(copied == "key", stringResource(R.string.pubkey_copy_key_description))
                    }
                }
                Text(
                    stringResource(R.string.pubkey_summary, key.keyType, key.fingerprint),
                    style = FlocketMono.small.copy(lineHeight = 18.sp),
                )
            }
        }
    }
}

@Composable
private fun CopyIcon(
    done: Boolean,
    description: String? = null,
) = Icon(
    if (done) Icons.Outlined.Done else Icons.Outlined.ContentCopy,
    contentDescription = description,
    modifier = Modifier.size(ButtonDefaults.IconSize),
)
