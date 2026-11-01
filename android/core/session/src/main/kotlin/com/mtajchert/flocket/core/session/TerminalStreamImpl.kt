package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.protocol.AttachChannel
import com.mtajchert.flocket.core.protocol.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Close [this] channel ignoring failures, but never swallowing cancellation. */
internal suspend fun AttachChannel.closeQuietly(
    logger: Logger,
    tag: String,
) {
    try {
        close()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug(tag) { "close channel $channel: ${e.javaClass.simpleName}" }
    }
}

/** What a durable stream needs from its session. */
internal interface StreamHost {
    /** The channel of [stream] ended without ending the stream: attach again when a link is up. */
    fun reattachSoon(stream: TerminalStreamImpl)

    fun onFinished(stream: TerminalStreamImpl)
}

/**
 * One attach channel of a durable stream. [epoch] = connection epoch it belongs to. The mutable
 * fields describe what was sent on this channel and are touched only under [TerminalStreamImpl.io].
 */
internal class Attached(
    val epoch: Long,
    val gen: Long,
    val channel: AttachChannel,
    var active: Boolean,
    var sentCols: Int,
    var sentRows: Int,
    var sentDisplay: DisplayFacts?,
    var sentFocus: Boolean? = null,
)

/** See [TerminalStream]: survives reconnects and `stop()`/`start()` by re-attaching with `resumed:true`. */
internal class TerminalStreamImpl(
    override val address: TerminalAddress,
    val mode: AttachMode,
    val historyLines: Int,
    cols: Int,
    rows: Int,
    display: DisplayFacts?,
    private val scope: CoroutineScope,
    private val host: StreamHost,
    private val logger: Logger,
) : TerminalStream {
    private class Slot(
        val attached: Attached?,
        val ended: Boolean,
    )

    private val slot = MutableStateFlow(Slot(null, false))
    private val sizeState = MutableStateFlow(TerminalSize(cols, rows))
    private val connectedState = MutableStateFlow(false)
    private val endState = MutableStateFlow<StreamEnd?>(null)
    private val gens = AtomicLong(0)
    private val takenGen = AtomicLong(0)
    private val collecting = AtomicBoolean(false)
    private val detaching = AtomicBoolean(false)

    /** Serializes everything written on the channel (controls + input) so controls precede later input. */
    val io = Mutex()

    /** Serializes (re)attach attempts. */
    val attachMutex = Mutex()

    private val lock = Any()

    /** Guards transitions of [slot], [connectedState] and [endState] together. */
    private val stateLock = Any()
    private var desiredCols = cols
    private var desiredRows = rows
    private var desiredDisplay = display
    private var desiredFocus: Boolean? = null

    override val size: StateFlow<TerminalSize> = sizeState.asStateFlow()
    override val connected: StateFlow<Boolean> = connectedState.asStateFlow()
    override val end: StateFlow<StreamEnd?> = endState.asStateFlow()

    val current: Attached? get() = slot.value.attached
    val isEnded: Boolean get() = endState.value != null
    val isDetaching: Boolean get() = detaching.get()

    /** Re-collectable (one collector at a time): a new collector gets a fresh attach and first paint. */
    override val output: Flow<ByteArray> =
        flow {
            check(collecting.compareAndSet(false, true)) { "TerminalStream.output has a single collector at a time" }
            try {
                while (true) {
                    val s = slot.first { it.ended || (it.attached != null && it.attached.gen > takenGen.get()) }
                    val a = s.attached
                    if (a != null && a.gen > takenGen.get()) {
                        takenGen.set(a.gen)
                        a.channel.output.collect { emit(it) }
                    } else {
                        return@flow
                    }
                }
            } finally {
                collecting.set(false)
            }
        }

    fun desiredSize(): Pair<Int, Int> = synchronized(lock) { desiredCols to desiredRows }

    fun desiredDisplay(): DisplayFacts? = synchronized(lock) { desiredDisplay }

    /**
     * Install a fresh channel; [active] = attached as size owner. Returns it for monitoring, or
     * null when the handle already ended (the caller must close [channel]). Atomic with [finish]
     * and [markDisconnected] so an ended handle never shows a live channel.
     */
    fun install(
        epoch: Long,
        channel: AttachChannel,
        active: Boolean,
        cols: Int,
        rows: Int,
        display: DisplayFacts?,
    ): Attached? {
        val a = Attached(epoch, gens.incrementAndGet(), channel, active, cols, rows, display)
        synchronized(stateLock) {
            if (endState.value != null) return null
            slot.value = Slot(a, false)
            connectedState.value = true
        }
        if (channel.terminal.cols > 0 && channel.terminal.rows > 0) {
            sizeState.value = TerminalSize(channel.terminal.cols, channel.terminal.rows)
        }
        return a
    }

    fun markDisconnected(a: Attached) {
        synchronized(stateLock) {
            if (slot.value.attached?.gen == a.gen) connectedState.value = false
        }
    }

    fun updateSize(
        cols: Int,
        rows: Int,
    ) {
        if (cols > 0 && rows > 0) sizeState.value = TerminalSize(cols, rows)
    }

    /** Ends the handle once; closes a still-open channel. */
    fun finish(end: StreamEnd) {
        val ch =
            synchronized(stateLock) {
                if (endState.value != null) return
                endState.value = end
                connectedState.value = false
                val attached = slot.value.attached
                slot.value = Slot(attached, true)
                attached?.channel
            }
        host.onFinished(this)
        if (ch != null && ch.closeReason.value == null) scope.launch { ch.closeQuietly(logger, TAG) }
    }

    override suspend fun write(bytes: ByteArray): AppResult<Unit> {
        if (isEnded || !connectedState.value) return AppResult.Failure(AppError.ConnectionLost)
        if (mode == AttachMode.Observe) return AppResult.Failure(AppError.InvalidInput("input", "observe-only stream"))
        return try {
            io.withLock {
                val a = current
                if (a == null || a.channel.closeReason.value != null || !connectedState.value) {
                    return AppResult.Failure(AppError.ConnectionLost)
                }
                a.active = true
                syncControls(a)
                a.channel.send(bytes)
            }
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bytes handed to the transport before the link died may have been typed: OutcomeUnknown.
            AppResult.Failure(e.toAppError("terminal.input", mutating = true))
        }
    }

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        if (cols <= 0 || rows <= 0) return
        synchronized(lock) {
            desiredCols = cols
            desiredRows = rows
        }
        controlSoon(activate = true)
    }

    override fun focus(focused: Boolean) {
        synchronized(lock) { desiredFocus = focused }
        controlSoon(activate = focused)
    }

    override fun updateDisplay(facts: DisplayFacts) {
        synchronized(lock) { desiredDisplay = facts }
        controlSoon(activate = false)
    }

    override suspend fun detach() {
        if (!detaching.compareAndSet(false, true)) return
        val ch = current?.channel
        if (ch != null && ch.closeReason.value == null) {
            try {
                ch.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(TAG) { "detach $address: ${e.javaClass.simpleName}" }
            }
        }
        finish(StreamEnd.Detached)
    }

    private fun controlSoon(activate: Boolean) {
        if (mode == AttachMode.Observe || isEnded) return
        scope.launch {
            try {
                io.withLock {
                    val a = current ?: return@withLock
                    if (a.channel.closeReason.value != null) return@withLock
                    if (activate) a.active = true
                    syncControls(a)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(TAG) { "control on $address failed: ${e.javaClass.simpleName}" }
            }
        }
    }

    /** Send what differs from what this channel already has. Passive (inactive) viewers send nothing. */
    private suspend fun syncControls(a: Attached) {
        if (!a.active || mode == AttachMode.Observe) return
        val (cols, rows, display, focus) =
            synchronized(lock) {
                Desired(desiredCols, desiredRows, desiredDisplay, desiredFocus)
            }
        if (cols != a.sentCols || rows != a.sentRows) {
            a.channel.resize(cols, rows)
            a.sentCols = cols
            a.sentRows = rows
        }
        if (display != null && display != a.sentDisplay) {
            a.channel.facts(display.toDisplayDto())
            a.sentDisplay = display
        }
        // A viewer starts unfocused: FOCUS 0 only after a FOCUS 1 was sent on this channel.
        if (focus != null && focus != (a.sentFocus ?: false)) {
            a.channel.focus(focus)
            a.sentFocus = focus
        }
    }

    private data class Desired(
        val cols: Int,
        val rows: Int,
        val display: DisplayFacts?,
        val focus: Boolean?,
    )

    private companion object {
        const val TAG = "TerminalStream"
    }
}
