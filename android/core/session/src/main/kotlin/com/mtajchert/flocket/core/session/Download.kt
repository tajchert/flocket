package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError

/** Events of one [CoreSession.download]. Exactly one terminal event ([Done] or [Failed]) ends the flow. */
sealed interface DownloadEvent {
    /** The remote file was checked; [resume] identifies it (pass it back to continue after a failure). */
    data class Started(
        val size: Long,
        val method: DownloadMethod,
        val resume: DownloadResume,
        /** Bytes already in the target from an earlier attempt. */
        val resumedFrom: Long,
    ) : DownloadEvent

    data class Progress(
        val bytes: Long,
        val size: Long,
    ) : DownloadEvent

    data class Done(
        val size: Long,
        val method: DownloadMethod,
    ) : DownloadEvent

    /** [resume] is non-null when the partial target can be continued with it. */
    data class Failed(
        val error: AppError,
        val resume: DownloadResume?,
    ) : DownloadEvent
}

enum class DownloadMethod { SFTP, CHUNKED }

/** Identity of the remote file when a download started; a resume is valid only while size and mtime still match. */
data class DownloadResume(
    val machine: String?,
    val path: String,
    val size: Long,
    val mtimeSeconds: Long,
)
