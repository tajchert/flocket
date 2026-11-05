package com.mtajchert.flocket.feature.terminal.impl.share

import androidx.compose.runtime.Immutable
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.ui.error.UiError
import java.io.File

/** One share as the screen shows it. */
@Immutable
data class ShareItem(
    val id: String,
    /** Absolute paths on [machine]. */
    val paths: List<String>,
    /** The federated machine the files live on; null = the host's own machine. */
    val machine: String?,
    val note: String?,
    val atEpochSeconds: Long,
) {
    /** File name of the first path (the card title); the rest are counted. */
    val name: String get() = baseName(paths.firstOrNull().orEmpty())
    val fileCount: Int get() = paths.size
}

internal fun AgentShare.toItem() = ShareItem(id, paths, machine, note, atEpochSeconds)

internal fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** Download state of the file shown in the viewer. */
sealed interface FileLoad {
    /** Nothing chosen yet (a share with several files starts here). */
    data object Idle : FileLoad

    /** [size] is null until the remote file was checked. */
    data class Loading(
        val path: String,
        val bytes: Long = 0,
        val size: Long? = null,
    ) : FileLoad

    /** [askInstall]: true once, right after an APK finished downloading while shown. */
    data class Loaded(
        val path: String,
        val file: File,
        val size: Long,
        val askInstall: Boolean = false,
    ) : FileLoad

    data class Failed(
        val path: String,
        val error: UiError,
        val canResume: Boolean,
    ) : FileLoad
}

@Immutable
data class ViewerUi(
    val share: ShareItem,
    val file: FileLoad = FileLoad.Idle,
)

@Immutable
data class SharesUi(
    /** The newest unseen share of this terminal, if any. */
    val card: ShareItem? = null,
    /** All shares of this terminal, newest first. */
    val all: List<ShareItem> = emptyList(),
    val sheetOpen: Boolean = false,
    val viewer: ViewerUi? = null,
    /** Downloads running: path to fraction (0..1), null while the size is unknown. */
    val downloading: Map<String, Float?> = emptyMap(),
    /** The last APK install result, shown once as a message and then cleared. */
    val installResult: ApkInstallResult? = null,
)
