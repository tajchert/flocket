package com.mtajchert.flocket.core.model

/** Largest file (raw bytes) Flocket uploads in one transfer, or reads whole with `readFile`; checked before reading it. */
const val MAX_TRANSFER_BYTES: Long = 8L * 1024 * 1024

/** Largest file (raw bytes) `CoreSession.download` streams to disk (ADR-0012): 200 MiB. */
const val MAX_DOWNLOAD_BYTES: Long = 200L * 1024 * 1024

/** A file `terminal.put` stored next to a terminal: [path] on the terminal's machine, safe to paste. */
data class PutReceipt(
    val path: String,
    val bytes: Long,
)
