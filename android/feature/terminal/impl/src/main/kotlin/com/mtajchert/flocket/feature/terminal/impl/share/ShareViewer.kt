package com.mtajchert.flocket.feature.terminal.impl.share

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.ui.component.ErrorState
import com.mtajchert.flocket.core.ui.error.UiError
import com.mtajchert.flocket.feature.terminal.impl.R

/** What the viewer's body shows. Decoding happens before this (see [ShareOverlays]); content is stateless. */
sealed interface ViewerBody {
    /** A share with several files: pick one. */
    data class Files(
        val paths: List<String>,
    ) : ViewerBody

    data object Loading : ViewerBody

    data class Failed(
        val error: UiError,
    ) : ViewerBody

    class Image(
        val bitmap: ImageBitmap,
    ) : ViewerBody

    class Text(
        val text: String,
        val cut: Boolean,
    ) : ViewerBody

    /** No preview: name and size are shown. */
    data object Other : ViewerBody
}

/** The viewer screen. [path] is the chosen file, null while choosing; [sizeBytes] is known once downloaded. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareViewerContent(
    share: ShareItem,
    path: String?,
    body: ViewerBody,
    sizeBytes: Long?,
    canPaste: Boolean,
    actions: ShareActions,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = body is ViewerBody.Image || body is ViewerBody.Text || body is ViewerBody.Other
    val canGoBack = path != null && share.fileCount > 1
    Scaffold(
        modifier = modifier.testTag("share_viewer"),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = actions.onBackToList) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.share_viewer_back))
                        }
                    } else {
                        IconButton(onClick = actions.onCloseViewer) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.share_viewer_close))
                        }
                    }
                },
                title = {
                    Text(
                        text = path?.let(::baseName) ?: stringResource(R.string.share_viewer_files_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                share.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (path != null) {
                    Detail(stringResource(R.string.share_viewer_path), path, mono = true)
                }
                Detail(
                    stringResource(R.string.share_viewer_machine),
                    share.machine ?: stringResource(R.string.share_viewer_machine_local),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (body) {
                    is ViewerBody.Files -> {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            body.paths.forEach { p ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            baseName(p),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = { Text(p, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(Icons.Outlined.InsertDriveFile, null) },
                                    modifier = Modifier.clickable { actions.onOpenFile(p) },
                                )
                            }
                        }
                    }

                    ViewerBody.Loading -> {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.testTag("share_loading"))
                            Text(
                                stringResource(R.string.share_viewer_loading),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    is ViewerBody.Failed -> {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            ErrorState(body.error)
                            val retryable =
                                body.error.source.let {
                                    it !is AppError.FileMissing &&
                                        it !is AppError.FileTooLarge
                                }
                            if (retryable && path != null) {
                                TextButton(onClick = { actions.onOpenFile(path) }) {
                                    Text(stringResource(R.string.share_viewer_retry))
                                }
                            }
                        }
                    }

                    is ViewerBody.Image -> {
                        Image(
                            bitmap = body.bitmap,
                            contentDescription =
                                stringResource(
                                    R.string.share_viewer_image_alt,
                                    path?.let(::baseName).orEmpty(),
                                ),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    }

                    is ViewerBody.Text -> {
                        Column(Modifier.fillMaxSize()) {
                            if (body.cut) {
                                Text(
                                    stringResource(R.string.share_viewer_text_cut),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = body.text,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier =
                                        Modifier
                                            .verticalScroll(rememberScrollState())
                                            .horizontalScroll(rememberScrollState())
                                            .padding(12.dp)
                                            .testTag("share_text"),
                                )
                            }
                        }
                    }

                    ViewerBody.Other -> {
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(48.dp))
                            Text(path?.let(::baseName).orEmpty(), style = MaterialTheme.typography.titleMedium)
                            sizeBytes?.let {
                                Text(
                                    Formatter.formatShortFileSize(LocalContext.current, it),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                stringResource(R.string.share_viewer_no_preview),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (path != null) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onSave, enabled = loaded, modifier = Modifier.testTag("share_save")) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Row(Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.share_save)) }
                    }
                    OutlinedButton(onClick = onShare, enabled = loaded, modifier = Modifier.testTag("share_share")) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Row(Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.share_share)) }
                    }
                    OutlinedButton(
                        onClick = actions.onPastePath,
                        enabled = canPaste,
                        modifier = Modifier.testTag("share_paste_path"),
                    ) {
                        Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                        Row(Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.share_paste_path)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
