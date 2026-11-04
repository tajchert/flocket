package com.mtajchert.flocket.feature.terminal.impl.transfer

import com.mtajchert.flocket.core.ui.error.UiError

/** Where an upload (paste or send to folder) is. [Done] and [Idle] show nothing on screen. */
sealed interface TransferPhase {
    data object Idle : TransferPhase

    /** Reading the picked content off the device. */
    data object Reading : TransferPhase

    data class Uploading(
        val name: String,
    ) : TransferPhase

    /** Uploaded; [path] is the file's path on the terminal's machine. */
    data class Done(
        val path: String,
    ) : TransferPhase

    data class Failed(
        val error: UiError,
    ) : TransferPhase

    /** The connection broke after the request was written: it may not have been uploaded. Never retried automatically. */
    data class OutcomeUnknown(
        val name: String,
    ) : TransferPhase
}

/** The "Send file to folder" dialog: read-only [folder], editable [name], [exists] asks for an explicit Replace. */
data class SendToFolderState(
    val name: String,
    val folder: String,
    val exists: Boolean = false,
    /** `test -e` gave no clear answer: treated like an existing target (explicit Replace). */
    val checkFailed: Boolean = false,
    val phase: TransferPhase = TransferPhase.Idle,
)
