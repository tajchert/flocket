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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.feature.terminal.impl.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal const val FALLBACK_MIME = "application/octet-stream"

/** MIME type from the file extension; [FALLBACK_MIME] when unknown. */
internal fun mimeFor(name: String): String =
    SharePreview
        .extension(name)
        .takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: FALLBACK_MIME

/** Decodes with a target size computed from the header, so a large image never takes its full size in memory. */
internal fun decodeImage(file: File): Bitmap? =
    try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val (w, h) = SharePreview.targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun readPrefix(
    file: File,
    max: Int,
): ByteArray =
    file.inputStream().use { input ->
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = input.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        buf.copyOf(n)
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
    val resources = LocalResources.current
    val message by rememberUpdatedState(onMessage)
    // The controller keeps the result until it is shown, so it survives the system install dialog.
    ui.installResult?.let { result ->
        LaunchedEffect(result) {
            message(
                when (result) {
                    is ApkInstallResult.Installed -> {
                        resources.getString(R.string.apk_installed, result.label)
                    }

                    is ApkInstallResult.Failed -> {
                        result.message?.takeIf { it.isNotBlank() }?.let {
                            resources.getString(R.string.apk_install_failed_reason, result.label, it)
                        } ?: resources.getString(R.string.apk_install_failed, result.label)
                    }

                    is ApkInstallResult.Cancelled -> {
                        resources.getString(R.string.apk_install_cancelled, result.label)
                    }
                },
            )
            actions.onInstallResultShown()
        }
    }
    if (ui.sheetOpen) {
        SharedFilesSheet(ui.all, remember(ui.all) { Clock.System.nowMillis() / 1000 }, actions, ui.downloading)
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
    val resources = LocalResources.current
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

    // The prompt flag changes once; the body must not be rebuilt for it.
    val stable = if (file is FileLoad.Loaded) file.copy(askInstall = false) else file
    val body by produceState<ViewerBody>(ViewerBody.Loading(), stable) {
        value =
            when (file) {
                FileLoad.Idle -> {
                    ViewerBody.Files(viewer.share.paths)
                }

                is FileLoad.Loading -> {
                    ViewerBody.Loading(file.bytes, file.size)
                }

                is FileLoad.Failed -> {
                    ViewerBody.Failed(file.error, file.canResume)
                }

                is FileLoad.Loaded -> {
                    withContext(Dispatchers.IO) { loadedBody(context, file, name, mime) }
                }
            }
    }
    val apk = body as? ViewerBody.Apk

    var promptApk by remember { mutableStateOf(false) }
    var permissionDialog by remember { mutableStateOf(false) }
    LaunchedEffect(loaded?.askInstall) {
        if (loaded?.askInstall == true) {
            promptApk = true
            actions.onInstallPromptShown()
        }
    }

    val installFile by rememberUpdatedState(loaded?.file)
    val installInfo by rememberUpdatedState(apk?.info)
    val doInstall: () -> Unit = {
        val f = installFile
        val info = installInfo
        if (f != null && info != null) {
            actions.onInstallStarted()
            scope.launch {
                val result = withContext(Dispatchers.IO) { ApkInstall.install(context, f, info) }
                if (result is AppResult.Failure) {
                    message(resources.getString(R.string.apk_install_failed, info.label))
                }
            }
        }
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (ApkInstall.canInstall(context)) {
                doInstall()
            } else {
                message(resources.getString(R.string.apk_permission_still_off))
            }
        }
    val startInstall: () -> Unit = {
        if (ApkInstall.canInstall(context)) doInstall() else permissionDialog = true
    }

    val saveLauncher =
        rememberLauncherForActivityResult(remember(mime) { ActivityResultContracts.CreateDocument(mime) }) { uri ->
            val source = loaded?.file
            if (uri != null && source == null) {
                message(resources.getString(R.string.share_save_failed))
            } else if (uri != null && source != null) {
                message(resources.getString(R.string.share_saving))
                scope.launch {
                    val ok =
                        withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.openOutputStream(uri, "w")!!.use { out ->
                                    source.inputStream().use { it.copyTo(out, 64 * 1024) }
                                }
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                false
                            }
                        }
                    message(resources.getString(if (ok) R.string.share_saved else R.string.share_save_failed))
                }
            }
        }

    if (promptApk && apk != null) {
        val info = apk.info
        if (info == null) {
            promptApk = false
        } else {
            ApkInstallDialog(
                info = info,
                onInstall = {
                    promptApk = false
                    startInstall()
                },
                onDismiss = { promptApk = false },
            )
        }
    }
    if (permissionDialog) {
        ApkPermissionDialog(
            onOpenSettings = {
                permissionDialog = false
                permissionLauncher.launch(ApkInstall.permissionSettingsIntent(context))
            },
            onDismiss = { permissionDialog = false },
        )
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
            sizeBytes = loaded?.size,
            canPaste = canPaste,
            actions = actions.copy(onInstall = startInstall),
            onSave = { saveLauncher.launch(ShareFiles.safeSegment(name)) },
            onShare = {
                val f = loaded?.file
                if (f != null) {
                    try {
                        context.startActivity(Intent.createChooser(sendIntent(context, f, mime), null))
                    } catch (_: Exception) {
                        message(resources.getString(R.string.share_share_failed))
                    }
                }
            },
        )
    }
}

/** Reads the preview from the file: the first 64 KiB decide, an image is decoded from the file itself. */
private fun loadedBody(
    context: Context,
    loaded: FileLoad.Loaded,
    name: String,
    mime: String,
): ViewerBody {
    if (ApkInstall.isApk(name)) return ViewerBody.Apk(ApkInstall.readInfo(context, loaded.file))
    return try {
        val prefix = readPrefix(loaded.file, SharePreview.PREFIX_BYTES)
        when (SharePreview.classify(name, mime, prefix, loaded.size)) {
            PreviewKind.Image -> {
                decodeImage(loaded.file)?.let { ViewerBody.Image(it.asImageBitmap()) } ?: ViewerBody.Other
            }

            PreviewKind.Text -> {
                val bytes = readPrefix(loaded.file, minOf(loaded.size, SharePreview.MAX_TEXT_BYTES + 1L).toInt())
                SharePreview.textLines(bytes).let { (lines, cut) -> ViewerBody.Text(lines, cut) }
            }

            PreviewKind.Other -> {
                ViewerBody.Other
            }
        }
    } catch (_: java.io.IOException) {
        ViewerBody.Other
    }
}

private fun sendIntent(
    context: Context,
    file: File,
    mime: String,
): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(file.name, uri) }
}
