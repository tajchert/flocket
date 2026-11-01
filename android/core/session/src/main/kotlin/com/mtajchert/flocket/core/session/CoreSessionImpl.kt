package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.CoreInfo
import com.mtajchert.flocket.core.model.Liveness
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalTemplate
import com.mtajchert.flocket.core.protocol.AttachChannel
import com.mtajchert.flocket.core.protocol.AttachParams
import com.mtajchert.flocket.core.protocol.ChannelEnd
import com.mtajchert.flocket.core.protocol.ConnectionOptions
import com.mtajchert.flocket.core.protocol.CoreConnection
import com.mtajchert.flocket.core.protocol.CoreNotification
import com.mtajchert.flocket.core.protocol.EventSubscription
import com.mtajchert.flocket.core.protocol.RunParams
import com.mtajchert.flocket.core.protocol.dto.toModel
import com.mtajchert.flocket.core.protocol.dto.toRecord
import com.mtajchert.flocket.core.protocol.machineExec
import com.mtajchert.flocket.core.protocol.machineList
import com.mtajchert.flocket.core.protocol.rpc.RpcException
import com.mtajchert.flocket.core.protocol.templatesList
import com.mtajchert.flocket.core.protocol.terminalInterrupt
import com.mtajchert.flocket.core.protocol.terminalKill
import com.mtajchert.flocket.core.protocol.terminalRead
import com.mtajchert.flocket.core.protocol.terminalRm
import com.mtajchert.flocket.core.protocol.terminalRun
import com.mtajchert.flocket.core.protocol.terminalSend
import com.mtajchert.flocket.core.protocol.toAppError
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.wire.ProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * [CoreSession] for one endpoint. Hosts are independent: create one instance per endpoint; nothing
 * is shared between instances except the caller's [scope].
 *
 * [transportFactory] opens a fresh byte stream to `hc connect` (SSH + `hc` discovery live in
 * core:data); it is called once per connection attempt and must report trust/auth problems as
 * the matching [AppError] (those stop retries until [retryNow]).
 */
class CoreSessionImpl(
    override val endpoint: CoreEndpoint,
    private val transportFactory: suspend () -> AppResult<ByteTransport>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val logger: Logger = Logger.None,
    private val config: SessionConfig = SessionConfig(),
) : CoreSession {
    /** One live connection. Everything derived from it carries its [epoch]. */
    private class Link(
        val epoch: Long,
        val conn: CoreConnection,
    ) {
        val info: CoreInfo get() = conn.info
    }

    private val stateFlow = MutableStateFlow(SessionState(endpoint))
    override val state: StateFlow<SessionState> = stateFlow.asStateFlow()

    private val reducer = EventReducer(endpoint)
    private val epochs = AtomicLong(0)
    private val link = AtomicReference<Link?>(null)
    private val homes = ConcurrentHashMap<Pair<String, String?>, String>()
    private val streams = CopyOnWriteArraySet<TerminalStreamImpl>()
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /** Guards [lastSeq] and the reducer-driven parts of [stateFlow]; never held across suspension. */
    private val reduceLock = Any()
    private var lastSeq = 0L

    private val loopLock = Any()
    private var loopJob: Job? = null
    private var previousLoop: Job? = null

    @Volatile
    private var parkOnExit = false

    private val connectionOptions = ConnectionOptions(clock = clock, logger = logger)

    // ---- lifecycle -----------------------------------------------------------------------------

    override fun start() {
        synchronized(loopLock) {
            if (loopJob?.isActive == true) return
            parkOnExit = false
            val prev = previousLoop
            // A new loop starts only after the previous one fully closed its link: never two connections.
            loopJob =
                scope
                    .launch {
                        prev?.join()
                        runLoop()
                    }.also { previousLoop = it }
        }
    }

    override suspend fun stop() {
        val job =
            synchronized(loopLock) {
                parkOnExit = true
                loopJob.also { loopJob = null }
            }
        epochs.incrementAndGet()
        job?.cancelAndJoin()
        synchronized(loopLock) {
            // A start() racing this stop() owns the state now; don't overwrite its Connecting.
            if (loopJob == null) stateFlow.update { it.copy(connection = ConnectionState.Idle) }
        }
    }

    override fun retryNow() {
        when (state.value.connection) {
            is ConnectionState.Reconnecting, is ConnectionState.NeedsAttention -> retrySignal.trySend(Unit)
            ConnectionState.Idle -> start()
            else -> Unit
        }
    }

    private suspend fun runLoop() {
        var failures = 0
        while (true) {
            val epoch = epochs.incrementAndGet()
            setConnection(epoch, ConnectionState.Connecting(failures + 1))
            val (error, wasOnline) =
                try {
                    runLink(epoch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let a bug kill the loop for good (the session would hang in Online/Connecting).
                    logger.warn(TAG, { "connection loop failed: ${e.javaClass.simpleName}" })
                    AppError.Unexpected(e) to false
                }
            failures = if (wasOnline) 1 else failures + 1
            if (error.needsUser()) {
                logger.info(TAG) { "${endpoint.hostId.value}: needs attention (${error.javaClass.simpleName})" }
                retrySignal.tryReceive() // drop a stale press before showing the prompt
                setConnection(epoch, ConnectionState.NeedsAttention(error))
                retrySignal.receive()
                failures = 0
                continue
            }
            val delayMs = config.backoffDelayMs(failures)
            retrySignal.tryReceive() // a press made before this wait must not skip it
            setConnection(epoch, ConnectionState.Reconnecting(failures, error, clock.nowMillis() + delayMs))
            withTimeoutOrNull(delayMs) { retrySignal.receive() }
        }
    }

    private fun setConnection(
        epoch: Long,
        c: ConnectionState,
    ) {
        if (epochs.get() != epoch) return
        stateFlow.update { it.copy(connection = c) }
    }

    /** One connection attempt and, if it comes up, its whole life. Returns why it ended. */
    private suspend fun runLink(epoch: Long): Pair<AppError, Boolean> {
        var conn: CoreConnection? = null
        var online: Link? = null
        try {
            val transport =
                when (val r = obtainTransport()) {
                    is AppResult.Success -> r.value
                    is AppResult.Failure -> return r.error to false
                }
            val c =
                try {
                    CoreConnection.open(transport, scope, connectionOptions)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return e.toAppError("connect") to false
                }
            conn = c
            val sub =
                try {
                    c.subscribeEvents(config.subscribeTimeout)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return e.toAppError("events.subscribe") to false
                }
            val machines = if (c.info.supports(CoreInfo.MACHINE_LIST)) fetchMachines(c) else emptyList()
            val l = Link(epoch, c)
            synchronized(reduceLock) {
                if (epochs.get() != epoch) return AppError.ConnectionLost to false
                lastSeq = sub.seq
                val snap = reducer.snapshot(stateFlow.value.terminals, sub.terminals, sub.seq)
                link.set(l)
                stateFlow.update {
                    it.copy(
                        connection = ConnectionState.Online(c.info),
                        core = c.info,
                        terminals = snap.terminals,
                        machines = machines ?: it.machines,
                        confirmedAtMs = clock.nowMillis(),
                    )
                }
            }
            online = l
            publishSizes()
            logger.info(
                TAG,
            ) { "${endpoint.hostId.value}: online (core ${c.info.version}, ${sub.terminals.size} terminals)" }
            coroutineScope {
                val watchdog = launch { watchdog(c) }
                streams.forEach { s -> launch { reattach(s, l) } }
                consumeEvents(l, sub)
                watchdog.cancel()
            }
            val cause = c.end.value?.cause
            return (cause?.toAppError("connection") ?: AppError.ConnectionLost) to true
        } finally {
            withContext(NonCancellable) {
                if (online != null) {
                    link.compareAndSet(online, null)
                    val c = online.conn
                    stateFlow.update { it.copy(confirmedAtMs = maxOf(it.confirmedAtMs ?: 0, c.lastReceivedAtMs)) }
                    if (parkOnExit) park(c)
                }
                conn?.close()
            }
        }
    }

    /** `stop()`: send CLOSE on every live attach channel (best effort, bounded) so the core re-applies sizes now. */
    private suspend fun park(c: CoreConnection) {
        val open = streams.mapNotNull { s -> s.current?.channel?.takeIf { it.closeReason.value == null } }
        if (open.isEmpty() || c.isClosed) return
        val j = scope.launch { open.forEach { ch -> ch.closeQuietly(logger, TAG) } }
        withTimeoutOrNull(config.detachGrace) { j.join() }
    }

    /** Calls the factory so that a transport produced after we gave up (cancel/stop) is closed, never leaked. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun obtainTransport(): AppResult<ByteTransport> {
        val result = CompletableDeferred<AppResult<ByteTransport>>()
        val job =
            scope.launch {
                val r =
                    try {
                        transportFactory()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppResult.Failure(AppError.Unexpected(e))
                    }
                if (!result.complete(r)) (r as? AppResult.Success)?.value?.close()
            }
        try {
            return result.await()
        } catch (e: CancellationException) {
            if (!result.complete(AppResult.Failure(AppError.ConnectionLost))) {
                val late = result.getCompleted()
                withContext(NonCancellable) { (late as? AppResult.Success)?.value?.close() }
            }
            job.cancel()
            throw e
        }
    }

    private suspend fun fetchMachines(c: CoreConnection) =
        try {
            c.machineList(config.readTimeout).map { it.toModel() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, { "machine.list failed: ${e.javaClass.simpleName}" })
            null
        }

    private suspend fun watchdog(c: CoreConnection) {
        while (true) {
            delay(config.watchdogInterval)
            if (clock.nowMillis() - c.lastReceivedAtMs >= config.watchdogTimeout.inWholeMilliseconds) {
                logger.warn(TAG, { "${endpoint.hostId.value}: no bytes for ${config.watchdogTimeout}, dropping link" })
                c.close()
                return
            }
        }
    }

    // ---- events --------------------------------------------------------------------------------

    /** Consume notifications; resubscribe on desync (bounded). Returns when the connection is gone. */
    private suspend fun consumeEvents(
        l: Link,
        first: EventSubscription,
    ) {
        var sub = first
        var resyncs = 0
        while (true) {
            val resync = sub.notifications.firstOrNull { n -> apply(l, n) }
            if (l.conn.isClosed) return
            if (resync == null) logger.warn(TAG, { "event channel ended (${sub.end.value}); resubscribing" })
            try {
                sub.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.debug(TAG) { "unsubscribe failed: ${e.javaClass.simpleName}" }
            }
            if (++resyncs > config.maxResyncsPerConnection) {
                logger.warn(TAG, { "too many resyncs; rebuilding connection" })
                l.conn.close()
                return
            }
            sub =
                try {
                    l.conn.subscribeEvents(config.subscribeTimeout)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    l.conn.close()
                    return
                }
            synchronized(reduceLock) {
                if (link.get() !== l) return
                lastSeq = sub.seq
                val snap = reducer.snapshot(stateFlow.value.terminals, sub.terminals, sub.seq)
                stateFlow.update { it.copy(terminals = snap.terminals, confirmedAtMs = clock.nowMillis()) }
            }
            publishSizes()
        }
    }

    /** Applies one notification if [l] is still current. Returns true when a resync is needed. */
    private fun apply(
        l: Link,
        n: CoreNotification,
    ): Boolean {
        val effect: ReduceEffect?
        synchronized(reduceLock) {
            if (link.get() !== l) return false // stale epoch: drop
            val before = stateFlow.value
            val r = reducer.reduce(ReducerState(before.terminals, lastSeq), n, clock.nowMillis())
            lastSeq = r.state.lastSeq
            effect = r.effect
            if (r.state.terminals !== before.terminals) {
                stateFlow.update {
                    it.copy(
                        terminals = r.state.terminals,
                        confirmedAtMs =
                            if (effect ==
                                ReduceEffect.SnapshotReplaced
                            ) {
                                clock.nowMillis()
                            } else {
                                it.confirmedAtMs
                            },
                    )
                }
            }
        }
        publishSizes()
        when (effect) {
            is ReduceEffect.Resync -> {
                logger.warn(TAG, { "desync: ${effect.reason}" })
                return true
            }

            ReduceEffect.SnapshotReplaced -> {
                if (l.info.supports(CoreInfo.MACHINE_LIST)) {
                    scope.launch {
                        val m = fetchMachines(l.conn) ?: return@launch
                        if (link.get() === l) stateFlow.update { it.copy(machines = m) }
                    }
                }
            }

            is ReduceEffect.DocChanged -> {
                logger.debug(TAG) { "doc ${effect.name} changed (documents not tracked yet)" }
            }

            null -> {
                Unit
            }
        }
        return false
    }

    private fun publishSizes() {
        val terminals = stateFlow.value.terminals
        for (s in streams) {
            terminals.firstOrNull { it.address == s.address }?.let { s.updateSize(it.cols, it.rows) }
        }
    }

    // ---- streams -------------------------------------------------------------------------------

    override suspend fun attach(
        address: TerminalAddress,
        request: AttachRequest,
    ): AppResult<TerminalStream> {
        val l = link.get() ?: return AppResult.Failure(AppError.ConnectionLost)
        val stream =
            TerminalStreamImpl(
                address,
                request.mode,
                request.historyLines,
                request.cols,
                request.rows,
                request.display,
                scope,
                host,
                logger,
            )
        val ch =
            try {
                l.conn.attach(
                    attachParams(stream, resumed = request.mode == AttachMode.Passive),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AppResult.Failure(e.toAppError("terminal.attach"))
            }
        val attached =
            stream.install(l.epoch, ch, request.mode == AttachMode.Owner, request.cols, request.rows, request.display)
                ?: error("fresh stream cannot be ended")
        streams.add(stream)
        monitor(stream, attached)
        return AppResult.Success(stream)
    }

    private fun attachParams(
        s: TerminalStreamImpl,
        resumed: Boolean,
    ): AttachParams {
        val (cols, rows) = s.desiredSize()
        return AttachParams(
            terminal = s.address.value,
            cols = cols,
            rows = rows,
            observe = s.mode == AttachMode.Observe,
            resumed = resumed,
            history = s.historyLines,
            display = s.desiredDisplay()?.toDisplayDto(),
            timeout = config.attachTimeout,
        )
    }

    /**
     * Re-attach [s] on [l] with `resumed:true` unless it already has a live channel there. A
     * transient failure while [l] is still current is retried with backoff (otherwise the handle
     * would stay disconnected until the next link drop). Attaching is not a write: retrying it is safe.
     */
    private suspend fun reattach(
        s: TerminalStreamImpl,
        l: Link,
        attempt: Int = 1,
    ) {
        val retry =
            s.attachMutex.withLock {
                if (s.isEnded || s.isDetaching || link.get() !== l) return
                val cur = s.current
                if (cur != null && cur.epoch == l.epoch && cur.channel.closeReason.value == null) return
                val (cols, rows) = s.desiredSize()
                val display = s.desiredDisplay()
                val ch: AttachChannel =
                    try {
                        l.conn.attach(attachParams(s, resumed = true))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RpcException) {
                        return@withLock !endFromReattachError(s, e.kind)
                    } catch (e: Exception) {
                        logger.debug(TAG) { "reattach ${s.address} failed: ${e.javaClass.simpleName}" }
                        return@withLock true
                    }
                val a =
                    if (link.get() === l && !s.isEnded && !s.isDetaching) {
                        s.install(l.epoch, ch, active = false, cols = cols, rows = rows, display = display)
                    } else {
                        null
                    }
                if (a == null) ch.closeQuietly(logger, TAG) else monitor(s, a)
                false
            }
        if (retry && link.get() === l && !s.isEnded && !s.isDetaching) {
            val wait = config.backoffDelayMs(attempt)
            scope.launch {
                delay(wait)
                reattach(s, l, attempt + 1)
            }
        }
    }

    /** Ends [s] for a terminal-state attach error; returns false when the error is transient. */
    private fun endFromReattachError(
        s: TerminalStreamImpl,
        kind: String,
    ): Boolean {
        val record = state.value.terminal(s.address)
        when (kind) {
            AppError.Rpc.FINISHED -> {
                val f = record?.liveness as? Liveness.Finished
                s.finish(StreamEnd.ProgramEnded(f?.reason, f?.exitCode, f?.signal))
            }

            AppError.Rpc.UNAVAILABLE -> {
                // A kept terminal waiting for its new shell is over for this handle; anything else
                // (e.g. a remote machine's line is down, SPEC §10) is transient: keep the handle parked.
                val waiting = record?.liveness as? Liveness.Waiting ?: return false
                s.finish(StreamEnd.ProgramLost(waiting.reason))
            }

            AppError.Rpc.NOT_FOUND, AppError.Rpc.MACHINE_UNKNOWN -> {
                s.finish(StreamEnd.Unknown)
            }

            else -> {
                logger.warn(TAG, { "reattach ${s.address}: rpc $kind; retrying" })
                return false
            }
        }
        return true
    }

    private fun monitor(
        s: TerminalStreamImpl,
        a: Attached,
    ) {
        scope.launch {
            val reason =
                a.channel.closeReason
                    .filterNotNull()
                    .first()
            if (s.current?.gen != a.gen) return@launch // superseded by a newer channel
            s.markDisconnected(a)
            when (reason) {
                ChannelEnd.LocalDetach -> {
                    if (s.isDetaching) s.finish(StreamEnd.Detached) else reattachSoon(s)
                }

                ChannelEnd.ClosedByCore -> {
                    s.finish(StreamEnd.Unknown)
                }

                is ChannelEnd.ProgramFinished -> {
                    s.finish(
                        StreamEnd.ProgramEnded(reason.reason, reason.exitCode, reason.signal),
                    )
                }

                is ChannelEnd.ProgramLost -> {
                    s.finish(StreamEnd.ProgramLost(reason.reason))
                }

                is ChannelEnd.ConnectionLost, ChannelEnd.Overflow -> {
                    reattachSoon(s)
                }
            }
        }
    }

    private fun reattachSoon(stream: TerminalStreamImpl) {
        val l = link.get() ?: return // parked: the next link re-attaches every stream
        scope.launch { reattach(stream, l) }
    }

    private val host =
        object : StreamHost {
            override fun reattachSoon(stream: TerminalStreamImpl) = this@CoreSessionImpl.reattachSoon(stream)

            override fun onFinished(stream: TerminalStreamImpl) {
                streams.remove(stream)
            }
        }

    // ---- actions -------------------------------------------------------------------------------

    override suspend fun homeDirectory(machine: String?): AppResult<String> {
        val l = link.get() ?: return AppResult.Failure(AppError.ConnectionLost)
        val key = l.info.id to machine
        homes[key]?.let { return AppResult.Success(it) }
        val r =
            read("machine.exec", CoreInfo.MACHINE_EXEC) { c ->
                val e = c.machineExec(HOME_ARGV, machine, config.readTimeout)
                val home =
                    runCatching { String(Base64.getDecoder().decode(e.stdout), Charsets.UTF_8) }.getOrDefault("")
                if (e.code != 0 || e.timedOut || !home.startsWith("/") || home.isBlank()) {
                    throw ProtocolException("home directory lookup failed (code=${e.code})")
                }
                home
            }
        if (r is AppResult.Success) homes[key] = r.value
        return r
    }

    /** Expands a leading `~` / `~/` (herdr-core does not). Null result = cannot expand. */
    private suspend fun expandCwd(request: RunRequest): AppResult<String> {
        val cwd = request.cwd
        if (cwd != "~" && !cwd.startsWith("~/")) {
            if (cwd.startsWith("~")) {
                return AppResult.Failure(AppError.InvalidInput("cwd", "cannot expand $cwd; use an absolute folder"))
            }
            return AppResult.Success(cwd)
        }
        return when (val h = homeDirectory(request.machine)) {
            is AppResult.Success -> {
                val rest = cwd.removePrefix("~").trimStart('/')
                AppResult.Success(if (rest.isEmpty()) h.value else h.value.trimEnd('/') + "/" + rest)
            }

            is AppResult.Failure -> {
                AppResult.Failure(AppError.InvalidInput("cwd", "cannot resolve the home folder for \"~\""))
            }
        }
    }

    override suspend fun run(request: RunRequest): AppResult<TerminalRecord> {
        val cwd =
            when (val e = expandCwd(request)) {
                is AppResult.Success -> e.value
                is AppResult.Failure -> return e
            }
        return runExpanded(request, cwd)
    }

    private suspend fun runExpanded(
        request: RunRequest,
        cwd: String,
    ): AppResult<TerminalRecord> =
        mutate("terminal.run", CoreInfo.TERMINAL_RUN) { c ->
            val argv = if (request.template != null) listOf(request.template) + request.argv else request.argv
            c
                .terminalRun(
                    RunParams(
                        argv = argv,
                        line = if (request.template != null) null else request.line,
                        name = request.name,
                        cwd = cwd,
                        on = request.machine,
                        env = RUN_ENV,
                        labels = request.labels,
                        keep = request.keep,
                    ),
                    config.runTimeout,
                ).terminal
                .toRecord(endpoint)
        }

    override suspend fun kill(address: TerminalAddress): AppResult<TerminalRecord> =
        mutate("terminal.kill", CoreInfo.TERMINAL_KILL) {
            it.terminalKill(address.value, config.killTimeout).toRecord(endpoint)
        }

    override suspend fun remove(address: TerminalAddress): AppResult<Unit> =
        mutate("terminal.rm", CoreInfo.TERMINAL_RM) {
            it.terminalRm(address.value, config.readTimeout)
            Unit
        }

    override suspend fun sendText(
        address: TerminalAddress,
        text: String,
        force: Boolean,
    ): AppResult<SendReceipt> =
        mutate("terminal.send", CoreInfo.TERMINAL_SEND) {
            val r = it.terminalSend(address.value, text, force, config.sendTimeout)
            SendReceipt(r.status, r.turn, r.started)
        }

    override suspend fun interrupt(address: TerminalAddress): AppResult<InterruptOutcome> =
        mutate("terminal.interrupt", CoreInfo.TERMINAL_INTERRUPT) {
            when (it.terminalInterrupt(address.value, config.interruptTimeout).outcome) {
                "stopped" -> InterruptOutcome.Stopped
                "exited" -> InterruptOutcome.Exited
                "still_working" -> InterruptOutcome.StillWorking
                else -> InterruptOutcome.Unknown
            }
        }

    override suspend fun templates(): AppResult<List<TerminalTemplate>> =
        read(
            "templates.list",
            CoreInfo.TEMPLATES_LIST,
        ) { c -> c.templatesList(config.readTimeout).map { it.toModel() } }

    override suspend fun readText(
        address: TerminalAddress,
        historyLines: Int,
    ): AppResult<String> =
        read("terminal.read", CoreInfo.TERMINAL_READ) {
            it.terminalRead(address.value, historyLines, config.readTimeout).text
        }

    /** At most once: never retried; a written-then-lost call is `OutcomeUnknown`. */
    private suspend fun <T> mutate(
        operation: String,
        method: String,
        block: suspend (CoreConnection) -> T,
    ): AppResult<T> {
        val l = link.get() ?: return AppResult.Failure(AppError.ConnectionLost)
        return call(l, operation, method, mutating = true, block)
    }

    /** Reads retry once on the next connection when the link dropped under them. */
    private suspend fun <T> read(
        operation: String,
        method: String,
        block: suspend (CoreConnection) -> T,
    ): AppResult<T> {
        val l = link.get() ?: return AppResult.Failure(AppError.ConnectionLost)
        val first = call(l, operation, method, mutating = false, block)
        if (first !is AppResult.Failure || first.error != AppError.ConnectionLost) return first
        val next =
            withTimeoutOrNull(config.readRetryWait) {
                state.first { link.get().let { it != null && it.epoch > l.epoch } }
                link.get()
            } ?: return first
        return call(next, operation, method, mutating = false, block)
    }

    private suspend fun <T> call(
        l: Link,
        operation: String,
        method: String,
        mutating: Boolean,
        block: suspend (CoreConnection) -> T,
    ): AppResult<T> {
        if (!l.info.supports(method)) return AppResult.Failure(AppError.Unsupported(method))
        return try {
            AppResult.Success(block(l.conn))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(e.toAppError(operation, mutating))
        }
    }

    companion object {
        private const val TAG = "CoreSession"
        private val HOME_ARGV = listOf("sh", "-c", "printf %s \"\$HOME\"")

        /** Terminal identity passed to every `terminal.run` (SPEC §4.4). */
        val RUN_ENV: Map<String, String> =
            mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor", "TERM_PROGRAM" to "Flocket")
    }
}

/** Builds sessions; one per endpoint. Convenience for DI in core:data. */
fun interface CoreSessionFactory {
    fun create(
        endpoint: CoreEndpoint,
        transportFactory: suspend () -> AppResult<ByteTransport>,
    ): CoreSession
}

class DefaultCoreSessionFactory(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val logger: Logger = Logger.None,
    private val config: SessionConfig = SessionConfig(),
) : CoreSessionFactory {
    override fun create(
        endpoint: CoreEndpoint,
        transportFactory: suspend () -> AppResult<ByteTransport>,
    ): CoreSession = CoreSessionImpl(endpoint, transportFactory, scope, clock, logger, config)
}
