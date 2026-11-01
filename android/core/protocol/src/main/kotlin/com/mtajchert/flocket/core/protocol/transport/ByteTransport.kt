package com.mtajchert.flocket.core.protocol.transport

/**
 * A bidirectional byte stream to `hc connect` (protocol/SPEC.md §1). The protocol layer knows
 * nothing else about the connection: SSH exec (`core:ssh`), a local process (real-core tests) and
 * the in-memory fake core all implement this.
 *
 * Contract:
 * - [read] and [write] may be called concurrently from different coroutines, but each by at most
 *   one caller at a time.
 * - [read] suspends until at least one byte is available, returns the number of bytes copied into
 *   [buffer], or -1 at end of stream. Chunk boundaries carry no meaning.
 * - Failures surface as [TransportException]; cancellation propagates as usual.
 * - [close] is idempotent and makes pending/future [read]s return -1 or throw.
 * - stdout is protocol only; anything the remote side printed to stderr is available, bounded, via
 *   [diagnostics] (for error messages such as "hc: command not found").
 */
interface ByteTransport {
    suspend fun read(buffer: ByteArray): Int

    suspend fun write(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    )

    /** Tail of the remote stderr (bounded, e.g. last 8 KiB). Empty if none. */
    fun diagnostics(): String

    suspend fun close()
}

/** The transport failed (I/O error, channel closed unexpectedly, remote exited). */
class TransportException(
    message: String,
    cause: Throwable? = null,
    val exitStatus: Int? = null,
) : java.io.IOException(message, cause)
