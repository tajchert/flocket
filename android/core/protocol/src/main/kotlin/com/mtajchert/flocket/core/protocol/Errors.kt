package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.protocol.rpc.RpcException
import com.mtajchert.flocket.core.protocol.transport.TransportException
import com.mtajchert.flocket.core.protocol.wire.ProtocolException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The connection is gone (EOF, I/O error, protocol violation, local close).
 *
 * [written] tells whether the request had (possibly) reached the wire: `true` means the bytes were
 * handed to the transport, so the host may have executed it; the session maps such mutating calls
 * to `AppError.OutcomeUnknown`. `false` means it certainly was not sent.
 */
class ConnectionClosedException(
    message: String,
    val written: Boolean,
    cause: Throwable? = null,
    /** Tail of the remote stderr at the time, e.g. `hc: command not found`. */
    val diagnostics: String = "",
) : java.io.IOException(message, cause)

/** No reply within the timeout. The call may still execute on the host if [written]. */
class RpcTimeoutException(
    val method: String,
    val timeout: Duration,
    val written: Boolean,
) : java.io.IOException("$method timed out after $timeout")

/** hello succeeded but the core cannot be used by this app (SPEC §2.2). */
class CoreIncompatibleException(
    val error: AppError.CoreIncompatible,
) : RuntimeException(error.reason)

/** Too many calls in flight (bounded, SPEC §14: 256). */
class TooManyPendingCallsException(
    limit: Int,
) : IllegalStateException("more than $limit calls pending")

/** The bounded outgoing input queue is full; input was NOT sent. */
class InputQueueFullException(
    limit: Int,
) : IllegalStateException("outgoing input queue exceeds $limit bytes")

/** The stream channel already ended; see [end]. */
class ChannelClosedException(
    val end: ChannelEnd,
) : IllegalStateException("channel closed: $end")

/**
 * Converts a protocol-layer failure into the app taxonomy. Rethrows [CancellationException].
 * For [ConnectionClosedException] with `written == true` and [mutating] == true the result is
 * `OutcomeUnknown(operation)`.
 */
fun Throwable.toAppError(
    operation: String,
    mutating: Boolean = false,
): AppError {
    if (this is CancellationException) throw this
    return when (this) {
        is RpcException -> {
            AppError.Rpc(kind, message.orEmpty(), hint)
        }

        is ConnectionClosedException -> {
            if (mutating &&
                written
            ) {
                AppError.OutcomeUnknown(operation)
            } else {
                AppError.ConnectionLost
            }
        }

        is RpcTimeoutException -> {
            if (mutating &&
                written
            ) {
                AppError.OutcomeUnknown(operation)
            } else {
                AppError.Timeout(operation)
            }
        }

        is CoreIncompatibleException -> {
            error
        }

        is ProtocolException -> {
            AppError.Protocol(message.orEmpty())
        }

        is TransportException -> {
            AppError.ConnectionLost
        }

        is InputQueueFullException -> {
            AppError.InvalidInput("input", message.orEmpty())
        }

        is ChannelClosedException -> {
            AppError.ConnectionLost
        }

        else -> {
            AppError.Unexpected(this)
        }
    }
}
