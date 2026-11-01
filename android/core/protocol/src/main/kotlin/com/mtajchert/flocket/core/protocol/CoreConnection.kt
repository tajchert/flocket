package com.mtajchert.flocket.core.protocol

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.protocol.dto.AttachResult
import com.mtajchert.flocket.core.protocol.dto.HelloResult
import com.mtajchert.flocket.core.protocol.dto.SubscribeResult
import com.mtajchert.flocket.core.protocol.dto.TerminalDto
import com.mtajchert.flocket.core.protocol.dto.toCoreInfo
import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import com.mtajchert.flocket.core.protocol.rpc.RpcException
import com.mtajchert.flocket.core.protocol.rpc.RpcMessage
import com.mtajchert.flocket.core.protocol.rpc.RpcRequest
import com.mtajchert.flocket.core.protocol.rpc.longId
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.wire.Frame
import com.mtajchert.flocket.core.protocol.wire.FrameCodec
import com.mtajchert.flocket.core.protocol.wire.FrameDecoder
import com.mtajchert.flocket.core.protocol.wire.FrameKind
import com.mtajchert.flocket.core.protocol.wire.HelloLineReader
import com.mtajchert.flocket.core.protocol.wire.ProtocolException
import com.mtajchert.flocket.core.protocol.wire.WireLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Tunables of a connection. Defaults follow protocol/SPEC.md §3.3 and §14. */
class ConnectionOptions(
    val helloTimeout: Duration = 10.seconds,
    val maxPendingCalls: Int = 256,
    /** Bytes of DATA/CLOSE frames kept for channels whose reply has not been processed yet. */
    val maxEarlyBytes: Int = 1 shl 20,
    val maxEarlyChannels: Int = 16,
    /** Outgoing input that may wait for the writer, across all channels. */
    val maxQueuedOutputBytes: Int = 1 shl 20,
    val clock: Clock = Clock.System,
    val logger: Logger = Logger.None,
)

/** Why the connection ended. [cause] is null for a local [CoreConnection.close]. */
class ConnectionEnd(
    val cause: Throwable?,
)

/**
 * One framed connection to a herdr-core (protocol/SPEC.md §3, §11): single reader coroutine,
 * serialized writer, id-matched concurrent calls, per-channel buffering bounded by the credit
 * window. Create with [open]. Everything fails with [ConnectionClosedException] once the link is
 * gone; the owner (the session) decides about reconnecting, this class never does.
 */
class CoreConnection private constructor(
    private val transport: ByteTransport,
    scope: CoroutineScope,
    private val options: ConnectionOptions,
    val info: CoreInfo,
    private val decoder: FrameDecoder,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    private val log = options.logger

    private val closed = AtomicBoolean(false)
    private val endState = MutableStateFlow<ConnectionEnd?>(null)

    /** Set once when the connection ended. */
    val end: StateFlow<ConnectionEnd?> = endState.asStateFlow()
    val isClosed: Boolean get() = closed.get()

    @Volatile
    private var lastReceived: Long = options.clock.nowMillis()

    /** Epoch ms of the last bytes read from the transport (any bytes count; heartbeats every 15 s). */
    val lastReceivedAtMs: Long get() = lastReceived

    private val writeMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val queuedOutput = AtomicLong()

    private val streamLock = Any()
    private val streams = HashMap<Int, StreamChannel>()
    private val early = HashMap<Int, EarlyBuffer>()

    /** Channels whose stream ended (core CLOSE seen); their ids are never reused (SPEC §3.4). */
    private val retired = HashSet<Int>()
    private var earlyBytes = 0

    /** A reply plus the stream the reader registered for it (attach/subscribe), owned by the caller. */
    private class Reply(
        val result: JsonElement,
        val stream: StreamChannel?,
    )

    /**
     * [deferred] is the single arbiter between the reader (complete) and a caller that gives up
     * (timeout/cancel: completeExceptionally). Whoever loses cleans up a registered stream, so a
     * reply racing a timeout can never leave a viewer/subscription behind (SPEC §3.4).
     */
    private class Pending(
        val deferred: CompletableDeferred<Reply>,
        val onReply: ((JsonElement) -> StreamChannel)?,
    ) {
        @Volatile var written = false
    }

    private class EarlyBuffer {
        val frames = ArrayList<Frame>()
        var overflow = false
    }

    // ---- calls ---------------------------------------------------------------------------------

    /**
     * Generic JSON-RPC call. Throws [RpcException] for an error reply, [ConnectionClosedException]
     * when the link is (or goes) down, [RpcTimeoutException] when no reply arrived in [timeout].
     * Never retries.
     */
    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeout: Duration,
    ): JsonElement = callInternal(method, params, timeout, null).result

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun callInternal(
        method: String,
        params: JsonObject,
        timeout: Duration,
        onReply: ((JsonElement) -> StreamChannel)?,
    ): Reply {
        if (closed.get()) throw closedException(written = false)
        if (pending.size >= options.maxPendingCalls) throw TooManyPendingCallsException(options.maxPendingCalls)
        val id = nextId.getAndIncrement()
        val p = Pending(CompletableDeferred(), onReply)
        pending[id] = p
        try {
            if (closed.get()) throw closedException(written = false)
            val bytes =
                FrameCodec.data(
                    0,
                    ProtocolJson
                        .encodeToString(
                            RpcRequest.serializer(),
                            RpcRequest(id = id, method = method, params = params),
                        ).toByteArray(),
                )
            val reply =
                withTimeoutOrNull(timeout) {
                    sendBytes(bytes) { p.written = true }
                    p.deferred.await()
                }
            return reply ?: throw RpcTimeoutException(method, timeout, p.written)
        } catch (e: Throwable) {
            // Lost the race against the reader: it already registered a stream nobody will own.
            if (!p.deferred.completeExceptionally(CancellationException("call abandoned")) &&
                !p.deferred.isCancelled
            ) {
                runCatching { p.deferred.getCompleted() }.getOrNull()?.stream?.let { closeStreamAsync(it) }
            }
            throw e
        } finally {
            pending.remove(id)
        }
    }

    // ---- events --------------------------------------------------------------------------------

    /** Unfiltered `events.subscribe {}` (SPEC §8.1) plus the initial 1 MiB credit boost. */
    suspend fun subscribeEvents(timeout: Duration = 15.seconds): EventSubscription {
        val reply =
            callInternal("events.subscribe", JsonObject(emptyMap()), timeout) { res ->
                val ch =
                    res.jsonObject["channel"]?.jsonPrimitive?.intOrNull
                        ?: throw ProtocolException("events.subscribe reply without channel")
                registerStream(StreamChannel(ch, WireLimits.WINDOW + WireLimits.EVENTS_CREDIT_BOOST + SLACK))
            }
        val st = checkNotNull(reply.stream)
        return ownedBy(st) {
            val result = ProtocolJson.decodeFromJsonElement(SubscribeResult.serializer(), reply.result)
            val snapshot = result.terminals.map(TerminalDto::parse)
            sendCredit(st.id, WireLimits.EVENTS_CREDIT_BOOST)
            EventSubscription(
                seq = result.seq,
                terminals = snapshot,
                channel = st.id,
                notifications = notifications(st),
                end = st.end.asStateFlow(),
                closer = { closeStream(st) },
            )
        }
    }

    /** Runs the post-reply decoding; on any failure (bad reply, cancellation) the stream is closed again. */
    private inline fun <T> ownedBy(
        st: StreamChannel,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Throwable) {
            closeStreamAsync(st)
            throw e
        }

    private fun notifications(st: StreamChannel): Flow<CoreNotification> =
        flow {
            check(st.collected.compareAndSet(false, true)) { "events can be collected only once" }
            val splitter = LineSplitter()
            try {
                while (true) {
                    val chunk = nextChunk(st) ?: break
                    for (line in splitter.feed(chunk)) {
                        parseNotification(line)?.let { emit(it) }
                    }
                    consumed(st, chunk.size)
                }
            } finally {
                // The only collector left: nobody can consume or credit any more, so unsubscribe.
                if (st.end.value == null) closeStreamAsync(st)
            }
        }

    private fun parseNotification(line: ByteArray): CoreNotification? {
        if (line.isEmpty()) return null
        return try {
            val msg = ProtocolJson.decodeFromString(RpcMessage.serializer(), line.decodeToString())
            val p = msg.params as? JsonObject
            when (msg.method) {
                "event" -> {
                    p ?: return null
                    CoreNotification.Event(
                        seq = p["seq"]?.jsonPrimitive?.longOrNull ?: return null,
                        type = p["type"]?.jsonPrimitive?.contentOrNull ?: return null,
                        terminal = p["terminal"]?.jsonPrimitive?.contentOrNull,
                        machine = p["machine"]?.jsonPrimitive?.contentOrNull,
                        data = p["data"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                }

                "heartbeat" -> {
                    CoreNotification.Heartbeat(p?.get("seq")?.jsonPrimitive?.longOrNull ?: return null)
                }

                "missed" -> {
                    p ?: return null
                    CoreNotification.Missed(
                        seq = p["seq"]?.jsonPrimitive?.longOrNull ?: return null,
                        terminals =
                            (p["terminals"] as? kotlinx.serialization.json.JsonArray).orEmpty().map(
                                TerminalDto::parse,
                            ),
                    )
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.warn(TAG, { "dropping unparseable event line (${line.size} bytes)" }, e)
            null
        }
    }

    // ---- attach --------------------------------------------------------------------------------

    /** `terminal.attach` (SPEC §4.5, §7). If the reply arrives after a timeout/cancel the channel is closed again (§3.4). */
    suspend fun attach(params: AttachParams): AttachChannel {
        val body =
            buildJsonObject {
                put("terminal", params.terminal)
                put("cols", params.cols)
                put("rows", params.rows)
                if (params.observe) put("observe", true)
                if (params.resumed) put("resumed", true)
                if (params.history > 0) put("history", params.history)
                params.display?.let {
                    put(
                        "display",
                        ProtocolJson.encodeToJsonElement(
                            com.mtajchert.flocket.core.protocol.dto.DisplayDto
                                .serializer(),
                            it,
                        ),
                    )
                }
            }
        val reply =
            callInternal("terminal.attach", body, params.timeout) { res ->
                val ch =
                    res.jsonObject["channel"]?.jsonPrimitive?.intOrNull
                        ?: throw ProtocolException("terminal.attach reply without channel")
                registerStream(StreamChannel(ch, WireLimits.WINDOW + WireLimits.ATTACH_CREDIT_BOOST + SLACK))
            }
        val st = checkNotNull(reply.stream)
        return ownedBy(st) {
            val result = ProtocolJson.decodeFromJsonElement(AttachResult.serializer(), reply.result)
            // A 256 KiB window caps a stream at 256 KiB per round trip (2.5 MB/s at 100 ms): grow it.
            sendCredit(st.id, WireLimits.ATTACH_CREDIT_BOOST)
            AttachChannelImpl(st, TerminalDto.parse(result.terminal))
        }
    }

    private inner class AttachChannelImpl(
        private val st: StreamChannel,
        override val terminal: TerminalDto,
    ) : AttachChannel {
        private val inputMutex = Mutex()
        override val channel: Int get() = st.id
        override val closeReason: StateFlow<ChannelEnd?> = st.end.asStateFlow()
        override val output: Flow<ByteArray> =
            flow {
                check(st.collected.compareAndSet(false, true)) { "attach output can be collected only once" }
                try {
                    while (true) {
                        val chunk = nextChunk(st) ?: break
                        emit(chunk)
                        consumed(st, chunk.size)
                    }
                } finally {
                    // The only collector left: detach rather than keep an uncredited viewer (size owner!).
                    if (st.end.value == null) closeStreamAsync(st)
                }
            }

        override suspend fun send(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            if (queuedOutput.addAndGet(bytes.size.toLong()) > options.maxQueuedOutputBytes) {
                queuedOutput.addAndGet(-bytes.size.toLong())
                throw InputQueueFullException(options.maxQueuedOutputBytes)
            }
            try {
                inputMutex.withLock {
                    var off = 0
                    while (off < bytes.size) {
                        st.end.value?.let { throw ChannelClosedException(it) }
                        val n = minOf(WireLimits.MAX_OUTGOING_DATA, bytes.size - off)
                        sendBytes(FrameCodec.data(st.id, bytes, off, n))
                        off += n
                    }
                }
            } finally {
                queuedOutput.addAndGet(-bytes.size.toLong())
            }
        }

        override suspend fun resize(
            cols: Int,
            rows: Int,
        ) {
            if (cols <= 0 || rows <= 0) return
            sendControl(FrameCodec.resize(st.id, cols.coerceAtMost(0xFFFF), rows.coerceAtMost(0xFFFF)))
        }

        override suspend fun focus(focused: Boolean) = sendControl(FrameCodec.focus(st.id, focused))

        override suspend fun facts(display: com.mtajchert.flocket.core.protocol.dto.DisplayDto) =
            sendControl(
                FrameCodec.facts(
                    st.id,
                    ProtocolJson
                        .encodeToString(
                            com.mtajchert.flocket.core.protocol.dto.DisplayDto
                                .serializer(),
                            display,
                        ).toByteArray(),
                ),
            )

        override suspend fun close() = closeStream(st)

        private suspend fun sendControl(bytes: ByteArray) {
            st.end.value?.let { throw ChannelClosedException(it) }
            sendBytes(bytes)
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /** Closes the transport; pending calls and streams fail. Terminals keep running on the host. */
    suspend fun close() = fail(null)

    private suspend fun fail(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            endState.value = ConnectionEnd(cause)
            val diag = runCatching { transport.diagnostics() }.getOrDefault("")
            val drained = pending.values.toList()
            pending.clear()
            for (p in drained) {
                p.deferred.completeExceptionally(
                    ConnectionClosedException("connection closed", p.written, cause, diag),
                )
            }
            synchronized(streamLock) {
                streams.values.forEach { finish(it, ChannelEnd.ConnectionLost(cause)) }
                streams.clear()
                early.clear()
            }
            runCatching { transport.close() }
            job.cancel()
        }
    }

    private fun closedException(written: Boolean): ConnectionClosedException {
        val cause = endState.value?.cause
        return ConnectionClosedException(
            "connection closed",
            written,
            cause,
            runCatching {
                transport.diagnostics()
            }.getOrDefault(""),
        )
    }

    // ---- writing -------------------------------------------------------------------------------

    private suspend fun sendBytes(
        bytes: ByteArray,
        onWriteStart: (() -> Unit)? = null,
    ) {
        if (closed.get()) throw closedException(false)
        var started = false
        try {
            withContext(NonCancellable) {
                writeMutex.withLock {
                    if (closed.get()) throw closedException(false)
                    started = true
                    onWriteStart?.invoke()
                    transport.write(bytes)
                }
            }
        } catch (e: ConnectionClosedException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException && !closed.get()) throw e
            fail(e)
            throw ConnectionClosedException(
                "write failed: ${e.message}",
                started,
                e,
                runCatching {
                    transport.diagnostics()
                }.getOrDefault(""),
            )
        }
    }

    private suspend fun sendCredit(
        channel: Int,
        n: Int,
    ) = sendBytes(FrameCodec.credit(channel, n))

    /**
     * Next chunk for the stream's single collector. Before suspending on an empty queue (the end of
     * a burst) the batched credit is returned, so unreturned credit never sits idle (SPEC §3.5).
     */
    private suspend fun nextChunk(st: StreamChannel): ByteArray? {
        st.rx
            .tryReceive()
            .getOrNull()
            ?.let { return it }
        flushCredit(st)
        return st.rx.receiveCatching().getOrNull()
    }

    /**
     * The collector finished [n] bytes. Credit is batched: one CREDIT per [CREDIT_BATCH] consumed
     * bytes or per burst ([nextChunk]), not one per DATA frame. Each CREDIT is a write through the
     * transport (over SSH: an encrypted packet), so per-frame credit made small chunks expensive.
     */
    private suspend fun consumed(
        st: StreamChannel,
        n: Int,
    ) {
        st.unconsumed.addAndGet(-n.toLong())
        st.pendingCredit += n
        if (st.pendingCredit >= CREDIT_BATCH) flushCredit(st)
    }

    private suspend fun flushCredit(st: StreamChannel) {
        val n = st.pendingCredit
        if (n == 0) return
        st.pendingCredit = 0
        if (st.end.value != null || closed.get()) return
        try {
            sendCredit(st.id, n)
        } catch (e: ConnectionClosedException) {
            // The reader will report the loss; the collector just sees the stream end.
        }
    }

    // ---- stream registry -----------------------------------------------------------------------

    private fun registerStream(st: StreamChannel): StreamChannel {
        var overflow = false
        synchronized(streamLock) {
            if (closed.get()) {
                finish(st, ChannelEnd.ConnectionLost(endState.value?.cause))
                return st
            }
            streams[st.id] = st
            val buffered = early.remove(st.id)
            if (buffered != null) {
                for (f in buffered.frames) {
                    earlyBytes -= f.payload.size
                    deliver(st, f)
                }
                overflow = buffered.overflow
                if (overflow) overflow(st)
            }
        }
        return st
    }

    /** Marks [st] as locally closed; true when a CLOSE frame must still be sent. */
    private fun markClosing(st: StreamChannel): Boolean {
        synchronized(streamLock) {
            if (st.closingLocally) return false
            val send = st.end.value == null
            st.closingLocally = true
            finish(st, ChannelEnd.LocalDetach)
            st.rx.cancel()
            return send && !closed.get()
        }
    }

    private suspend fun closeStream(st: StreamChannel) {
        if (!markClosing(st)) return
        try {
            sendBytes(FrameCodec.close(st.id))
        } catch (e: ConnectionClosedException) {
            // connection is gone anyway
        }
    }

    /** Non-suspending [closeStream] for the reader, abandoned calls and finished collectors. */
    private fun closeStreamAsync(st: StreamChannel) {
        if (markClosing(st)) scope.launch { runCatching { sendBytes(FrameCodec.close(st.id)) } }
    }

    private fun finish(
        st: StreamChannel,
        end: ChannelEnd,
    ) {
        st.end.compareAndSet(null, end)
        st.rx.close()
    }

    private fun overflow(st: StreamChannel) {
        st.closingLocally = true
        finish(st, ChannelEnd.Overflow)
        st.rx.cancel()
        scope.launch { runCatching { sendBytes(FrameCodec.close(st.id)) } }
    }

    // ---- reader --------------------------------------------------------------------------------

    private suspend fun readLoop() {
        var failure: Throwable? = null
        try {
            drainDecoder()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = transport.read(buf)
                if (n < 0) {
                    failure =
                        ConnectionClosedException(
                            "connection closed by remote",
                            true,
                            null,
                            runCatching {
                                transport.diagnostics()
                            }.getOrDefault(""),
                        )
                    break
                }
                lastReceived = options.clock.nowMillis()
                decoder.feed(buf, 0, n)
                drainDecoder()
            }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            fail(failure.takeUnless { it is CancellationException && closed.get() })
        }
    }

    private fun drainDecoder() {
        while (true) {
            val f = decoder.next() ?: return
            dispatch(f)
        }
    }

    private fun dispatch(f: Frame) {
        if (f.channel == 0) {
            if (f.kind == FrameKind.DATA) handleControl(f.payload)
            return
        }
        if (f.kind != FrameKind.DATA && f.kind != FrameKind.CLOSE) return // CREDIT etc. from the core: nothing to do
        synchronized(streamLock) {
            val st = streams[f.channel]
            if (st != null) {
                deliver(st, f)
                return
            }
            // Channels are never reused: stray frames of a finished stream must not occupy the early buffer.
            if (f.channel in retired) return
            val buf =
                early[f.channel]
                    ?: if (early.size >=
                        options.maxEarlyChannels
                    ) {
                        return
                    } else {
                        EarlyBuffer().also { early[f.channel] = it }
                    }
            if (buf.overflow) return
            if (earlyBytes + f.payload.size > options.maxEarlyBytes) {
                buf.overflow = true
                buf.frames.forEach { earlyBytes -= it.payload.size }
                buf.frames.clear()
                return
            }
            earlyBytes += f.payload.size
            buf.frames += f
        }
    }

    /** Must hold [streamLock]. Never suspends and never blocks on a slow collector. */
    private fun deliver(
        st: StreamChannel,
        f: Frame,
    ) {
        when (f.kind) {
            FrameKind.DATA -> {
                if (st.closingLocally || st.end.value != null) return
                if (st.unconsumed.addAndGet(f.payload.size.toLong()) > st.creditCap) {
                    overflow(st)
                } else {
                    st.rx.trySend(f.payload)
                }
            }

            FrameKind.CLOSE -> {
                finish(st, parseClose(f.payload))
                streams.remove(st.id)
                retired += st.id
            }
        }
    }

    private fun parseClose(payload: ByteArray): ChannelEnd {
        if (payload.isEmpty()) return ChannelEnd.ClosedByCore
        return try {
            val o = ProtocolJson.parseToJsonElement(payload.decodeToString()).jsonObject
            val reason = o["reason"]?.jsonPrimitive?.contentOrNull
            val exit = o["exit_code"]?.jsonPrimitive?.intOrNull
            val signal = o["signal"]?.jsonPrimitive?.intOrNull
            val finishedMs = o["finished_ms"]?.jsonPrimitive?.longOrNull
            if (finishedMs !=
                null
            ) {
                ChannelEnd.ProgramFinished(reason, exit, signal, finishedMs)
            } else {
                ChannelEnd.ProgramLost(reason, exit, signal)
            }
        } catch (e: Exception) {
            ChannelEnd.ClosedByCore
        }
    }

    private fun handleControl(payload: ByteArray) {
        val msg =
            try {
                ProtocolJson.decodeFromString(RpcMessage.serializer(), payload.decodeToString())
            } catch (e: Exception) {
                log.warn(TAG, { "unparseable channel-0 message (${payload.size} bytes)" }, e)
                return
            }
        if (msg.method != null) return // calls/notifications on channel 0 are for peer cores
        val id = msg.longId() ?: return
        val p = pending.remove(id)
        if (p == null || !p.deferred.isActive) {
            // Orphan reply (timeout/cancel): never leave a viewer or subscription behind (SPEC §3.4).
            val ch = (msg.result as? JsonObject)?.get("channel")?.let { (it as? JsonPrimitive)?.intOrNull }
            if (ch != null) {
                val st =
                    StreamChannel(ch, 0).also {
                        it.closingLocally = true
                        finish(it, ChannelEnd.LocalDetach)
                    }
                synchronized(streamLock) { if (streams[ch] == null) streams[ch] = st }
                synchronized(streamLock) { early.remove(ch)?.frames?.forEach { earlyBytes -= it.payload.size } }
                scope.launch { runCatching { sendBytes(FrameCodec.close(ch)) } }
            }
            return
        }
        val error = msg.error
        if (error != null) {
            p.deferred.completeExceptionally(RpcException.from(error))
            return
        }
        val result = msg.result ?: kotlinx.serialization.json.JsonNull
        val st =
            try {
                p.onReply?.invoke(result)
            } catch (e: Exception) {
                p.deferred.completeExceptionally(e)
                return
            }
        // The caller may have given up since the isActive check: then the stream is ours to close.
        if (!p.deferred.complete(Reply(result, st))) st?.let { closeStreamAsync(it) }
    }

    private class StreamChannel(
        val id: Int,
        val creditCap: Long,
    ) {
        val rx = Channel<ByteArray>(Channel.UNLIMITED)
        val unconsumed = AtomicLong()
        val end = MutableStateFlow<ChannelEnd?>(null)

        @Volatile var closingLocally = false
        val collected = AtomicBoolean(false)

        /** Consumed but not yet credited; only touched by the single collector. */
        var pendingCredit = 0
    }

    companion object {
        private const val TAG = "CoreConnection"
        private const val SLACK = 64L * 1024

        /** Return credit once this many bytes were consumed (or at the end of a burst). */
        internal const val CREDIT_BATCH = 32 * 1024

        const val GENERATION = 1

        /** Oldest supported core (SPEC §2.2 item 6). A minimum only: newer is always accepted. */
        const val MIN_CORE_VERSION = "0.1.16"
        val REQUIRED_FEATURES = setOf("framed", "attach", "events", "status")
        val REQUIRED_METHODS =
            setOf("hello", "events.subscribe", "terminal.list", "terminal.get", "terminal.attach", "doc.get")

        /**
         * Handshake (hello, compatibility policy §2.2), then start the reader. On any failure the
         * transport is closed. Fails with [CoreIncompatibleException], [ProtocolException] (garbage
         * before hello), [RpcException], [ConnectionClosedException] or [RpcTimeoutException].
         * The connection runs as a child of [scope]'s job.
         */
        suspend fun open(
            transport: ByteTransport,
            scope: CoroutineScope,
            options: ConnectionOptions = ConnectionOptions(),
        ): CoreConnection {
            try {
                val decoder = FrameDecoder()
                val hello = handshake(transport, decoder, options)
                val info = checkCompatible(hello)
                val conn = CoreConnection(transport, scope, options, info, decoder)
                conn.scope.launch { conn.readLoop() }
                return conn
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { transport.close() } }
                throw e
            }
        }

        private suspend fun handshake(
            transport: ByteTransport,
            decoder: FrameDecoder,
            options: ConnectionOptions,
        ): HelloResult {
            val request =
                """{"jsonrpc":"2.0","id":0,"method":"hello","params":{"generation":$GENERATION,"framed":true}}""" + "\n"
            val line =
                withTimeoutOrNull(options.helloTimeout) { readHelloLine(transport, request) }
                    ?: throw RpcTimeoutException("hello", options.helloTimeout, true)
            val msg =
                try {
                    ProtocolJson.decodeFromString(RpcMessage.serializer(), line.line.decodeToString())
                } catch (e: Exception) {
                    throw ProtocolException("unparseable hello reply", cause = e)
                }
            msg.error?.let { err ->
                val ex = RpcException.from(err)
                if (ex.kind == "unsupported_generation") {
                    throw CoreIncompatibleException(
                        AppError.CoreIncompatible("core does not speak protocol generation $GENERATION: ${ex.message}"),
                    )
                }
                throw ex
            }
            val result = msg.result ?: throw ProtocolException("hello reply without result")
            val hello = ProtocolJson.decodeFromJsonElement(HelloResult.serializer(), result)
            if (line.remainder.isNotEmpty()) decoder.feed(line.remainder)
            return hello
        }

        private suspend fun readHelloLine(
            transport: ByteTransport,
            request: String,
        ): HelloLineReader.Result {
            try {
                transport.write(request.toByteArray())
                val reader = HelloLineReader()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = transport.read(buf)
                    if (n <
                        0
                    ) {
                        throw ConnectionClosedException(
                            "connection closed before the hello reply",
                            true,
                            null,
                            transport.diagnostics(),
                        )
                    }
                    reader.feed(buf, 0, n)?.let { return it }
                }
            } catch (e: java.io.IOException) {
                if (e is ConnectionClosedException || e is ProtocolException) throw e
                throw ConnectionClosedException(
                    "transport failed during hello: ${e.message}",
                    true,
                    e,
                    transport.diagnostics(),
                )
            }
        }

        /** Parses `major.minor.patch`, ignoring any `-pre` / `+build` suffix; null when unparsable. */
        internal fun parseVersion(version: String): Triple<Int, Int, Int>? {
            val core = version.trim().substringBefore('+').substringBefore('-')
            val parts = core.split('.')
            if (parts.size < 3) return null
            val nums = parts.take(3).map { it.toIntOrNull() ?: return null }
            return Triple(nums[0], nums[1], nums[2])
        }

        internal fun isOlderThanMinimum(version: String): Boolean {
            val v = parseVersion(version) ?: return false
            val min = parseVersion(MIN_CORE_VERSION)!!
            return compareValuesBy(v, min, { it.first }, { it.second }, { it.third }) < 0
        }

        /**
         * SPEC §2.2: generation, framed, features, methods, and a *minimum* core version
         * ([MIN_CORE_VERSION]). Newer versions are always accepted and the version is never
         * compared for equality. An empty or unparsable version is accepted (lenient).
         */
        fun checkCompatible(hello: HelloResult): CoreInfo {
            fun bad(reason: String): Nothing =
                throw CoreIncompatibleException(AppError.CoreIncompatible(reason, hello.version.ifEmpty { null }))
            if (hello.generation !=
                GENERATION
            ) {
                bad("core speaks protocol generation ${hello.generation}, the app needs $GENERATION")
            }
            if (isOlderThanMinimum(hello.version)) {
                bad("hc ${hello.version} is too old; Flocket needs $MIN_CORE_VERSION or newer")
            }
            if (!hello.framed) bad("core did not switch to framed mode")
            val missingFeatures = REQUIRED_FEATURES - hello.features.toSet()
            if (missingFeatures.isNotEmpty()) bad("core lacks features: ${missingFeatures.sorted().joinToString()}")
            val missingMethods = REQUIRED_METHODS - hello.methods.toSet()
            if (missingMethods.isNotEmpty()) bad("core lacks methods: ${missingMethods.sorted().joinToString()}")
            return hello.toCoreInfo()
        }
    }
}

/** Splits a byte stream into `\n`-terminated lines across arbitrary chunk boundaries (SPEC gotcha #3). */
internal class LineSplitter(
    private val maxLine: Int = WireLimits.MAX_PAYLOAD,
) {
    private var partial = java.io.ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<ByteArray> {
        var out: MutableList<ByteArray>? = null
        var start = 0
        for (i in chunk.indices) {
            if (chunk[i] == NL) {
                partial.write(chunk, start, i - start)
                (out ?: ArrayList<ByteArray>().also { out = it }).add(partial.toByteArray())
                partial.reset()
                start = i + 1
            }
        }
        partial.write(chunk, start, chunk.size - start)
        if (partial.size() > maxLine) {
            partial.reset() // drop an absurd line rather than grow without bound
        }
        return out ?: emptyList()
    }

    private companion object {
        const val NL = '\n'.code.toByte()
    }
}
