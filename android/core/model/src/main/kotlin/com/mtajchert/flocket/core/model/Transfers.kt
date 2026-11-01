package com.mtajchert.flocket.core.model

/** Largest file (raw bytes) Flocket uploads or downloads in one transfer; checked before reading it whole. */
const val MAX_TRANSFER_BYTES: Long = 8L * 1024 * 1024

/** A file `terminal.put` stored next to a terminal: [path] on the terminal's machine, safe to paste. */
data class PutReceipt(
    val path: String,
    val bytes: Long,
)
