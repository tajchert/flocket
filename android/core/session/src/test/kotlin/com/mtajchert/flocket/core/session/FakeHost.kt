package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.protocol.testing.FakeCore
import com.mtajchert.flocket.core.protocol.testing.FakeCoreConfig
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.wire.FrameKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Transport wrapper that remembers whether the session closed it. */
class TrackingTransport(
    private val delegate: ByteTransport,
) : ByteTransport by delegate {
    @Volatile
    var closed = false
        private set

    override suspend fun close() {
        closed = true
        delegate.close()
    }
}

/**
 * A host whose `hc connect` yields a fresh [FakeCore] per connection (like a real reconnect),
 * seeded with [terminals]. Scripted failures/gates apply to the next factory calls in order.
 */
class FakeHost(
    private val scope: CoroutineScope,
) {
    val terminals = CopyOnWriteArrayList<JsonObject>()
    val cores = CopyOnWriteArrayList<FakeCore>()
    val transports = CopyOnWriteArrayList<TrackingTransport>()
    val failures = ArrayDeque<AppError>()

    /** Gate for the n-th (1-based) factory call; ignores cancellation like a blocking SSH connect. */
    val gates = HashMap<Int, CompletableDeferred<Unit>>()
    var configure: (FakeCore) -> Unit = {}
    var coreConfig = FakeCoreConfig()
    val calls = AtomicInteger()

    val core: FakeCore get() = cores.last()

    suspend fun factory(): AppResult<ByteTransport> {
        val n = calls.incrementAndGet()
        gates[n]?.let { g -> suspendCoroutine { cont -> g.invokeOnCompletion { cont.resume(Unit) } } }
        synchronized(failures) { failures.removeFirstOrNull() }?.let { return AppResult.Failure(it) }
        val core = FakeCore(scope, coreConfig)
        core.terminals.addAll(terminals)
        configure(core)
        cores += core
        return AppResult.Success(TrackingTransport(core.client).also { transports += it })
    }

    fun session(
        test: TestScope,
        config: SessionConfig = SessionConfig(random = FixedRandom(0.5)),
        id: String = "h1",
    ) = CoreSessionImpl(
        CoreEndpoint(HostId(id)),
        ::factory,
        test.backgroundScope,
        Clock { test.testScheduler.currentTime },
        config = config,
    )
}

suspend fun CoreSession.awaitConnection(predicate: (ConnectionState) -> Boolean): ConnectionState =
    state.first { predicate(it.connection) }.connection

suspend fun CoreSession.awaitOnline() = awaitConnection { it is ConnectionState.Online }

fun FakeCore.callsOf(method: String) = calls.filter { it.method == method }

fun FakeCore.controlKinds(channel: Int) =
    streamFrames
        .filter {
            it.channel == channel && it.kind in CONTROL_KINDS
        }.map { FrameKind.name(it.kind) }

private val CONTROL_KINDS = setOf(FrameKind.RESIZE, FrameKind.FOCUS, FrameKind.FACTS)

/** Collects [flow] into a growing text buffer in [scope]; returns the buffer. */
fun collectText(
    scope: CoroutineScope,
    flow: Flow<ByteArray>,
): StringBuffer {
    val sb = StringBuffer()
    scope.launch { flow.collect { sb.append(it.decodeToString()) } }
    return sb
}
