package com.mtajchert.flocket.core.ui.component

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.ui.R
import kotlinx.coroutines.launch

/** The one-liner that authorizes this device on the host. Single quotes in [publicKey] are escaped. */
fun authorizedKeysCommand(publicKey: String): String =
    "echo '${publicKey.trim().replace("'", "'\\''")}' >> ~/.ssh/authorized_keys"

/** Shows a device public key with copy, share and the `authorized_keys` one-liner. */
@Composable
fun PublicKeyCard(
    key: DeviceKey,
    modifier: Modifier = Modifier,
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
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pubkey_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.pubkey_summary, key.keyType, key.fingerprint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.pubkey_instruction), style = MaterialTheme.typography.bodyMedium)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { copy("command", command) }) {
                    CopyIcon(copied == "command")
                    Text(
                        stringResource(
                            if (copied ==
                                "command"
                            ) {
                                R.string.pubkey_copied
                            } else {
                                R.string.pubkey_copy_command
                            },
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(onClick = { copy("key", key.publicKeyOpenSsh) }) {
                    CopyIcon(copied == "key")
                    Text(
                        stringResource(if (copied == "key") R.string.pubkey_copied else R.string.pubkey_copy_key),
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
                ) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(stringResource(R.string.pubkey_share), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun CopyIcon(done: Boolean) =
    Icon(
        if (done) Icons.Outlined.Done else Icons.Outlined.ContentCopy,
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
    )
