package com.mtajchert.flocket.core.ui.component

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.ui.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an imported private key comes from. A file is the default: pasting key text is the fallback. */
enum class KeySource { File, Paste }

/** A key file the user picked. [name] is the display name, when the provider reports one. */
class PickedKeyFile(
    val name: String?,
    val text: String,
) {
    // Never print key material.
    override fun toString(): String = "PickedKeyFile(name=$name)"
}

/** Private keys are a few KB; anything bigger is not a key file. */
const val MAX_KEY_FILE_CHARS: Int = 64 * 1024

/**
 * Returns a function that opens the system file picker and reads the chosen key file off the main thread.
 * [onPick] gets the file; [onFail] runs when it can't be read or is too large to be a key.
 */
@Composable
fun rememberKeyFilePicker(
    onPick: (PickedKeyFile) -> Unit,
    onFail: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPick)
    val failed by rememberUpdatedState(onFail)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { readKeyFile(context.contentResolver, uri) }
                    if (file != null) picked(file) else failed()
                }
            }
        }
    // Key files usually have no extension or MIME type, so offer every file.
    return { launcher.launch(arrayOf("*/*")) }
}

/** Reads at most [MAX_KEY_FILE_CHARS]; returns null for unreadable, empty or larger files. */
internal fun readKeyFile(
    resolver: ContentResolver,
    uri: Uri,
): PickedKeyFile? =
    try {
        val text =
            resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val buf = CharArray(MAX_KEY_FILE_CHARS + 1)
                var total = 0
                while (total < buf.size) {
                    val n = reader.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                if (total in 1..MAX_KEY_FILE_CHARS) String(buf, 0, total) else null
            }
        text?.let { PickedKeyFile(displayName(resolver, uri), it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private fun displayName(
    resolver: ContentResolver,
    uri: Uri,
): String? =
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

/**
 * The key part of an import form: a "Choose key file" button (the default) or, after "Paste key text instead",
 * a text box. The picked key is never displayed, only the file name. [notAKey] flags the
 * active input when its text is not a private key.
 */
@Composable
fun KeySourceFields(
    source: KeySource,
    fileName: String?,
    pem: String,
    notAKey: Boolean,
    onPickFile: () -> Unit,
    onPemChange: (String) -> Unit,
    onSourceChange: (KeySource) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (source) {
            KeySource.File -> {
                val hasFile = fileName != null || pem.isNotEmpty()
                if (hasFile) {
                    PickedFileRow(
                        fileName ?: stringResource(R.string.key_import_file_unnamed),
                        if (notAKey) stringResource(R.string.key_import_not_a_key_file) else null,
                    )
                    OutlinedButton(onClick = onPickFile, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.key_import_choose_other_file))
                    }
                } else {
                    Button(onClick = onPickFile, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.key_import_choose_file), Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.key_import_file_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onSourceChange(KeySource.Paste) }, enabled = enabled) {
                    Text(stringResource(R.string.key_import_switch_to_paste))
                }
            }

            KeySource.Paste -> {
                OutlinedTextField(
                    value = pem,
                    onValueChange = onPemChange,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.key_import_pem)) },
                    minLines = 3,
                    maxLines = 6,
                    isError = notAKey,
                    supportingText = if (notAKey) ({ Text(stringResource(R.string.key_import_not_a_key)) }) else null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                )
                TextButton(onClick = { onSourceChange(KeySource.File) }, enabled = enabled) {
                    Text(stringResource(R.string.key_import_switch_to_file))
                }
            }
        }
    }
}

@Composable
private fun PickedFileRow(
    name: String,
    problem: String?,
) {
    val isError = problem != null
    val colors = MaterialTheme.colorScheme
    val container = if (isError) colors.errorContainer else colors.surfaceContainerHighest
    val primaryText = if (isError) colors.onErrorContainer else colors.onSurface
    val secondaryText = if (isError) colors.onErrorContainer else colors.onSurfaceVariant
    Surface(shape = MaterialTheme.shapes.small, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (isError) colors.onErrorContainer else colors.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = primaryText,
                )
                Text(
                    problem ?: stringResource(R.string.key_import_file_loaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryText,
                )
            }
        }
    }
}

/** A label suggestion from a key file name: `id_ed25519` stays, `work.pem` becomes `work`. */
fun keyLabelFromFileName(name: String?): String? =
    name
        ?.substringAfterLast('/')
        ?.let { n -> if (n.lastIndexOf('.') > 0) n.substringBeforeLast('.') else n }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** A cheap pre-check before handing text to the key parser: OpenSSH and PEM private keys say so in their header. */
fun looksLikePrivateKey(text: String): Boolean = text.contains("PRIVATE KEY")
