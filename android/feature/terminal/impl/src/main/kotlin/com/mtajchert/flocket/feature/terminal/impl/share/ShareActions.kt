package com.mtajchert.flocket.feature.terminal.impl.share

import androidx.compose.runtime.Immutable

/** What the share UI can ask for. Defaults are no-ops (previews, screenshot tests). */
@Immutable
data class ShareActions(
    val onOpen: (id: String) -> Unit = {},
    val onDismiss: (id: String) -> Unit = {},
    val onShowSheet: () -> Unit = {},
    val onHideSheet: () -> Unit = {},
    val onOpenFile: (path: String) -> Unit = {},
    val onBackToList: () -> Unit = {},
    val onCloseViewer: () -> Unit = {},
    val onPastePath: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onInstallPromptShown: () -> Unit = {},
    val onInstallStarted: () -> Unit = {},
    val onInstallResultShown: () -> Unit = {},
    /** The Install button of an APK; the overlays supply the platform part. */
    val onInstall: () -> Unit = {},
) {
    companion object {
        fun of(c: ShareController) =
            ShareActions(
                onOpen = c::open,
                onDismiss = c::dismiss,
                onShowSheet = c::showSheet,
                onHideSheet = c::hideSheet,
                onOpenFile = c::openFile,
                onBackToList = c::backToList,
                onCloseViewer = c::closeViewer,
                onPastePath = c::pastePath,
                onCancelDownload = c::cancelDownload,
                onResume = c::resume,
                onRetry = c::retry,
                onInstallPromptShown = c::installPromptShown,
                onInstallStarted = c::installStarted,
                onInstallResultShown = c::installResultShown,
            )
    }
}
