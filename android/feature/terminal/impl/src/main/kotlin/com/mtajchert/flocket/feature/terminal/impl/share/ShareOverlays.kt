package com.mtajchert.flocket.feature.terminal.impl.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.feature.terminal.impl.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

internal const val FALLBACK_MIME = "application/octet-stream"

/** MIME type from the file extension; [FALLBACK_MIME] when unknown. */
internal fun mimeFor(name: String): String =
    SharePreview
        .extension(name)
        .takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: FALLBACK_MIME

/** Decodes with a target size computed from the header, so a large image never takes its full size in memory. */
internal fun decodeImage(bytes: ByteArray): Bitmap? =
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val (w, h) = SharePreview.targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * The share sheet and the full-screen viewer, wired to the platform: image decoding, Save (the document picker)
 * and Share (FileProvider + chooser). [onMessage] shows a snackbar.
 */
@Composable
fun ShareOverlays(
    ui: SharesUi,
    actions: ShareActions,
    canPaste: Boolean,
    onMessage: (String) -> Unit,
) {
    if (ui.sheetOpen) {
        SharedFilesSheet(ui.all, remember(ui.all) { Clock.System.nowMillis() / 1000 }, actions)
    }
    ui.viewer?.let { ShareViewerDialog(it, canPaste, actions, onMessage) }
}

@Composable
private fun ShareViewerDialog(
    viewer: ViewerUi,
    canPaste: Boolean,
    actions: ShareActions,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)
    val file = viewer.file
    val loaded = file as? FileLoad.Loaded
    val path =
        when (file) {
            is FileLoad.Loaded -> file.path
            is FileLoad.Loading -> file.path
            is FileLoad.Failed -> file.path
            FileLoad.Idle -> null
        }
    val name = path?.let(::baseName).orEmpty()
    val mime = remember(name) { mimeFor(name) }

    LaunchedEffect(viewer.share.id) {
        withContext(Dispatchers.IO) { ShareFiles.prune(context.cacheDir, System.currentTimeMillis()) }
    }

    val body by produceState<ViewerBody>(ViewerBody.Loading, file) {
        value =
            when (file) {
                FileLoad.Idle -> {
                    ViewerBody.Files(viewer.share.paths)
                }

                is FileLoad.Loading -> {
                    ViewerBody.Loading
                }

                is FileLoad.Failed -> {
                    ViewerBody.Failed(file.error)
                }

                is FileLoad.Loaded -> {
                    withContext(Dispatchers.Default) {
                        when (SharePreview.classify(name, mime, file.bytes)) {
                            PreviewKind.Image -> {
                                decodeImage(file.bytes)?.let { ViewerBody.Image(it.asImageBitmap()) }
                                    ?: ViewerBody.Other
                            }

                            PreviewKind.Text -> {
                                SharePreview.textLines(file.bytes).let { (lines, cut) -> ViewerBody.Text(lines, cut) }
                            }

                            PreviewKind.Other -> {
                                ViewerBody.Other
                            }
                        }
                    }
                }
            }
    }

    val saveLauncher =
        rememberLauncherForActivityResult(remember(mime) { ActivityResultContracts.CreateDocument(mime) }) { uri ->
            val bytes = loaded?.bytes
            if (uri != null && bytes == null) {
                message(context.getString(R.string.share_save_failed))
            } else if (uri != null && bytes != null) {
                scope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                                true
                            } catch (_: Exception) {
                                false
                            }
                        }
                    message(context.getString(if (ok) R.string.share_saved else R.string.share_save_failed))
                }
            }
        }

    // Back from one file of several returns to the list; otherwise it closes.
    val back = if (path != null && viewer.share.fileCount > 1) actions.onBackToList else actions.onCloseViewer
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ShareViewerContent(
            share = viewer.share,
            path = path,
            body = body,
            sizeBytes = loaded?.bytes?.size?.toLong(),
            canPaste = canPaste,
            actions = actions,
            onSave = { saveLauncher.launch(ShareFiles.safeSegment(name)) },
            onShare = {
                val bytes = loaded?.bytes
                if (bytes != null) {
                    scope.launch {
                        try {
                            val f =
                                withContext(
                                    Dispatchers.IO,
                                ) { ShareFiles.write(context.cacheDir, viewer.share.id, name, bytes) }
                            context.startActivity(Intent.createChooser(sendIntent(context, f, mime), null))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            message(context.getString(R.string.share_share_failed))
                        }
                    }
                }
            },
        )
    }
}

private fun sendIntent(
    context: Context,
    file: java.io.File,
    mime: String,
): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(file.name, uri) }
}
