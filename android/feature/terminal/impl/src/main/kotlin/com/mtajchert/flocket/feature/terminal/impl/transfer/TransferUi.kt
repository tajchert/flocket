package com.mtajchert.flocket.feature.terminal.impl.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.ui.error.asString
import com.mtajchert.flocket.feature.terminal.impl.R
import com.mtajchert.flocket.feature.terminal.impl.StateBanner

/** The clipboard as the paste chooser sees it. */
data class ClipboardOffer(
    val hasText: Boolean,
    val hasImage: Boolean,
)

/** Bottom sheet behind the Paste action: text as today, the clipboard's image, a photo or any file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteChooserSheet(
    clipboard: ClipboardOffer,
    onPasteText: () -> Unit,
    onPasteClipboardImage: () -> Unit,
    onChoosePhoto: () -> Unit,
    onChooseFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        PasteChooserContent(clipboard, onPasteText, onPasteClipboardImage, onChoosePhoto, onChooseFile)
    }
}

@Composable
fun PasteChooserContent(
    clipboard: ClipboardOffer,
    onPasteText: () -> Unit,
    onPasteClipboardImage: () -> Unit,
    onChoosePhoto: () -> Unit,
    onChooseFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.navigationBarsPadding().padding(bottom = 8.dp).testTag("paste_chooser")) {
        Text(
            stringResource(R.string.transfer_chooser_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (clipboard.hasText) {
            ChooserRow(Icons.Outlined.ContentPaste, R.string.transfer_chooser_text, "paste_text", onPasteText)
        }
        if (clipboard.hasImage) {
            ChooserRow(
                Icons.Outlined.Image,
                R.string.transfer_chooser_clipboard_image,
                "paste_clipboard_image",
                onPasteClipboardImage,
            )
        }
        ChooserRow(Icons.Outlined.PhotoLibrary, R.string.transfer_chooser_photo, "paste_photo", onChoosePhoto)
        ChooserRow(
            Icons.AutoMirrored.Outlined.InsertDriveFile,
            R.string.transfer_chooser_file,
            "paste_file",
            onChooseFile,
        )
    }
}

@Composable
private fun ChooserRow(
    icon: ImageVector,
    label: Int,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

/** Progress, failure and unknown-outcome strip for a paste upload. Draws nothing for Idle and Done. */
@Composable
fun TransferStrip(
    phase: TransferPhase,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (phase) {
        TransferPhase.Reading -> {
            StateBanner(
                icon = Icons.Outlined.CloudUpload,
                text = stringResource(R.string.transfer_reading),
                actions = listOf(stringResource(R.string.transfer_cancel) to onCancel),
                progress = true,
            )
        }

        is TransferPhase.Uploading -> {
            StateBanner(
                icon = Icons.Outlined.CloudUpload,
                text = stringResource(R.string.transfer_uploading, phase.name),
                actions = listOf(stringResource(R.string.transfer_cancel) to onCancel),
                progress = true,
            )
        }

        is TransferPhase.OutcomeUnknown -> {
            StateBanner(
                icon = Icons.Outlined.ErrorOutline,
                text = stringResource(R.string.transfer_unknown, phase.name),
                actions =
                    listOf(
                        stringResource(R.string.terminal_dismiss) to onDismiss,
                        stringResource(R.string.terminal_retry) to onRetry,
                    ),
                error = true,
            )
        }

        is TransferPhase.Failed -> {
            StateBanner(
                icon = Icons.Outlined.ErrorOutline,
                text =
                    stringResource(
                        R.string.terminal_error_format,
                        phase.error.title.asString(),
                        phase.error.message.asString(),
                    ),
                actions =
                    buildList {
                        add(stringResource(R.string.terminal_dismiss) to onDismiss)
                        if (phase.error.source.isTransient()) add(stringResource(R.string.terminal_retry) to onRetry)
                    },
                error = true,
            )
        }

        TransferPhase.Idle, is TransferPhase.Done -> {
            Unit
        }
    }
}

/** Worth a manual Retry: the link, not the file or the request, was the problem. */
private fun AppError?.isTransient() =
    this is AppError.ConnectionLost || this is AppError.Timeout || this is AppError.Unreachable

/**
 * "Send file to folder": the folder is read-only (where the terminal's program is now), the name is editable.
 * An existing target asks for an explicit Replace; nothing is typed into the terminal.
 */
@Composable
fun SendToFolderDialog(
    state: SendToFolderState,
    onConfirm: (name: String, replace: Boolean) -> Unit,
    onCancelUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(state.name) { mutableStateOf(state.name) }
    val phase = state.phase
    val busy = phase is TransferPhase.Reading || phase is TransferPhase.Uploading
    val warnExists = (state.exists || state.checkFailed) && name.trim() == state.name
    val validName = name.isNotBlank() && '/' !in name
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.transfer_send_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.folder,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().testTag("send_folder"),
                    label = { Text(stringResource(R.string.transfer_send_folder)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("send_name"),
                    label = { Text(stringResource(R.string.transfer_send_name)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                when {
                    phase is TransferPhase.Reading -> {
                        Text(stringResource(R.string.transfer_reading), style = MaterialTheme.typography.bodyMedium)
                    }

                    phase is TransferPhase.Uploading -> {
                        Text(
                            stringResource(R.string.transfer_uploading, phase.name),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    phase is TransferPhase.OutcomeUnknown -> {
                        Text(
                            stringResource(R.string.transfer_send_unknown, phase.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    phase is TransferPhase.Failed -> {
                        Text(
                            stringResource(
                                R.string.terminal_error_format,
                                phase.error.title.asString(),
                                phase.error.message.asString(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    warnExists -> {
                        Text(
                            stringResource(
                                if (state.exists) R.string.transfer_send_exists else R.string.transfer_send_unchecked,
                                state.name,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!busy) {
                TextButton(
                    onClick = { onConfirm(name.trim(), warnExists) },
                    enabled = validName,
                    modifier = Modifier.testTag("send_confirm"),
                ) {
                    Text(
                        stringResource(
                            if (warnExists) R.string.transfer_send_replace else R.string.transfer_send_confirm,
                        ),
                        color = if (warnExists) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
        },
        dismissButton = {
            if (busy) {
                TextButton(onClick = onCancelUpload) { Text(stringResource(R.string.transfer_cancel)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_cancel)) }
            }
        },
    )
}
