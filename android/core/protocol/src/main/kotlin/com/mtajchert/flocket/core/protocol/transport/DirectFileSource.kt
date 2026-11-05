package com.mtajchert.flocket.core.protocol.transport

/**
 * A transport whose link can read files of the machine `hc connect` runs on directly, without the core
 * (SFTP on the same SSH session, ADR-0012). Non-SSH transports don't implement it; downloads then use
 * chunked `machine.exec` reads.
 */
interface DirectFileSource {
    /** Opens a reader on a new channel. Throws [DirectFileException] with [DirectFileException.Reason.UNAVAILABLE] when the host refuses SFTP. */
    suspend fun openFileReader(): DirectFileReader
}

/** One SFTP channel. Not thread-safe: one call at a time. */
interface DirectFileReader {
    /** null when [path] does not exist. Throws [DirectFileException] (`DENIED`, `IO`) or [TransportException]. */
    suspend fun stat(path: String): RemoteFileStat?

    /**
     * Copies [path] from byte [offset] to its end into [out], calling [onBytes] with each block's size.
     * Cancelling the caller closes the remote handle promptly. Returns the bytes copied.
     * Throws [DirectFileException] (`DENIED`, `MISSING`, `IO`) or [TransportException].
     */
    suspend fun copyTo(
        path: String,
        offset: Long,
        out: java.io.OutputStream,
        onBytes: (Int) -> Unit,
    ): Long

    /** Closes the channel. Idempotent, safe from a cancelled coroutine. */
    suspend fun close()
}

/** What a download needs to know about a remote file. [mtimeSeconds] is the modification time (epoch seconds). */
data class RemoteFileStat(
    val size: Long,
    val mtimeSeconds: Long,
    val isRegularFile: Boolean,
)

class DirectFileException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause) {
    enum class Reason { UNAVAILABLE, DENIED, MISSING, IO }
}
