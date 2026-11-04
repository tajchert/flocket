package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.error.AppException
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.protocol.ConnectionOptions
import com.mtajchert.flocket.core.protocol.CoreConnection
import com.mtajchert.flocket.core.protocol.toAppError
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.SiblingTransportSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * The session's transfer connection: a second `hc connect` used only for uploads and downloads, so
 * a multi-megabyte frame never blocks terminal output, keystrokes or other replies on the main
 * connection (design 2026-11-01 §4.1).
 *
 * Opened on demand: a sibling stream on the main transport's link when it is a
 * [SiblingTransportSource] (another exec channel on the same SSH session), else a new transport
 * from [fallback]. It negotiates `hello` like the main connection, runs one transfer at a time, and
 * closes after [idleTimeout] without a transfer or on [close]. It is independent of the main
 * connection's epochs: it stays up across a main reconnect while its own link lives.
 *
 * Never retries: the caller decides (reads once more, mutations never).
 */
internal class TransferChannel(
    private val scope: CoroutineScope,
    private val fallback: suspend () -> AppResult<ByteTransport>,
    private val options: ConnectionOptions,
    private val idleTimeout: Duration,
    private val logger: Logger,
) {
    private val mutex = Mutex()

    @Volatile
    private var conn: CoreConnection? = null

    @Volatile
    private var idleJob: Job? = null

    /** Bumped by [close]; a connection opened across a close is dropped, not kept. */
    private val generation = AtomicLong()

    /**
     * Runs [block] on the transfer connection, opening it first if needed. Failing to open throws
     * [AppException] (never an outcome-unknown: no transfer was sent); [block] throws what
     * [CoreConnection.call] throws.
     */
    suspend fun <T> use(
        main: ByteTransport,
        block: suspend (CoreConnection) -> T,
    ): T =
        mutex.withLock {
            idleJob?.cancel()
            idleJob = null
            try {
                block(connection(main))
            } finally {
                scheduleIdleClose()
            }
        }

    /** Closes the connection now; an in-flight transfer fails. The next [use] opens a new one. */
    suspend fun close() {
        generation.incrementAndGet()
        idleJob?.cancel()
        val c = conn
        conn = null
        c?.close()
    }

    private suspend fun connection(main: ByteTransport): CoreConnection {
        conn?.takeIf { !it.isClosed }?.let { return it }
        conn = null
        val gen = generation.get()
        val transport = openTransport(main)
        val c =
            try {
                CoreConnection.open(transport, scope, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AppException(e.toAppError("connect"), e)
            }
        if (generation.get() != gen) {
            withContext(NonCancellable) { c.close() }
            throw AppException(AppError.ConnectionLost)
        }
        conn = c
        return c
    }

    private suspend fun openTransport(main: ByteTransport): ByteTransport {
        if (main is SiblingTransportSource) {
            logger.debug(TAG) { "opening transfer connection (sibling)" }
            return try {
                main.openSibling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AppException(e.toAppError("connect"), e)
            }
        }
        logger.debug(TAG) { "opening transfer connection (new transport)" }
        return when (val r = fallback()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> throw AppException(r.error)
        }
    }

    /** Called with [mutex] held. */
    private fun scheduleIdleClose() {
        idleJob =
            scope.launch {
                delay(idleTimeout)
                val me = coroutineContext[Job]
                mutex.withLock {
                    if (idleJob !== me) return@withLock
                    idleJob = null
                    val c = conn
                    conn = null
                    if (c != null) logger.debug(TAG) { "transfer connection idle, closing" }
                    c?.close()
                }
            }
    }

    private companion object {
        const val TAG = "TransferChannel"
    }
}
