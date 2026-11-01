package com.mtajchert.flocket.core.protocol.testing

import com.mtajchert.flocket.core.protocol.rpc.ProtocolJson
import com.mtajchert.flocket.core.protocol.wire.Frame
import com.mtajchert.flocket.core.protocol.wire.FrameCodec
import com.mtajchert.flocket.core.protocol.wire.FrameDecoder
import com.mtajchert.flocket.core.protocol.wire.FrameKind
import com.mtajchert.flocket.core.protocol.wire.WireLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** An error reply for a scripted handler to throw. */
class FakeRpcError(
    val kind: String,
    message: String = kind,
    val hint: String = "",
    val code: Int = -32000,
) : RuntimeException(message)

/** What the fake core announces in `hello` and how it behaves around it. */
class FakeCoreConfig(
    val coreId: String = "0123456789abcdef0123456789abcdef",
    val coreName: String = "fakehost",
    val version: String = "9.9.9",
    val generation: Int = 1,
    val framed: Boolean = true,
    val features: List<String> = listOf("framed", "attach", "events", "status", "shell"),
    val methods: List<String> = DEFAULT_METHODS,
    /** Printed before anything else (simulates an SSH banner / MOTD on stdout). */
    val banner: String? = null,
    /** Max payload of a stream DATA frame the fake sends (the real core: 16384). */
    val streamChunk: Int = 16_384,
    /** Send the first paint BEFORE the attach reply (remote machines, SPEC §3.4). */
    val paintBeforeReply: Boolean = false,
    val firstPaint: ByteArray = "\u001b[!pfirst-paint\r\n$ ".toByteArray(),
) {
    companion object {
        val DEFAULT_METHODS =
            listOf(
                "hello",
                "server.status",
                "terminal.run",
                "templates.list",
                "terminal.list",
                "terminal.get",
                "terminal.kill",
                "terminal.rm",
                "terminal.label",
                "terminal.read",
                "machine.exec",
                "terminal.attach",
                "terminal.send",
                "terminal.keys",
                "terminal.interrupt",
                "doc.get",
                "doc.set",
                "events.subscribe",
                "machine.list",
            )
    }
}

/** A request the fake received on channel 0. */
data class FakeCall(
    val method: String,
    val params: JsonObject,
)

/**
 * Small scriptable in-memory herdr-core speaking the real wire format: plain `hello`, then framed
 * mode with concurrent request handling (replies may overtake each other), credit-limited stream
 * channels (initial window 256 KiB, chunks <= [FakeCoreConfig.streamChunk]), event channel with
 * snapshot, push helpers, attach channels with first paint that echo input, CLOSE echo.
 *
 * Usage: `val core = FakeCore(backgroundScope); CoreConnection.open(core.client, scope)`.
 * Single connection per instance. Scripting hooks: [handlers], [delays], [gates], [errors].
 */
class FakeCore(
    private val scope: CoroutineScope,
    val config: FakeCoreConfig = FakeCoreConfig(),
) {
    private val ends = PipeTransport.pair()

    /** Hand this to `CoreConnection.open`. */
    val client: PipeTransport = ends.first
    private val server: PipeTransport = ends.second

    /** Terminals returned by list/subscribe/get (full Terminal JSON objects). */
    val terminals = CopyOnWriteArrayList<JsonObject>()

    /** Override or add method handlers; throw [FakeRpcError] to reply with an error. */
    val handlers = ConcurrentHashMap<String, suspend (JsonObject) -> JsonElement>()

    /** Wait this long (virtual time works) before answering the method. */
    val delays = ConcurrentHashMap<String, Duration>()

    /** Complete the gate to release a held method: lets tests force out-of-order replies. */
    val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Shortcut for always failing a method with an error kind. */
    val errors = ConcurrentHashMap<String, FakeRpcError>()

    /** Every request received on channel 0, in arrival order. */
    val calls = CopyOnWriteArrayList<FakeCall>()

    /** Every frame received on a stream channel (>= 1), including CREDIT/RESIZE/FOCUS/FACTS/CLOSE. */
    val streamFrames = CopyOnWriteArrayList<Frame>()

    private val writeLock = Mutex()
    private val seq = AtomicLong(0)
    private val nextChannel = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, ServerStream>()
    private val credits = ConcurrentHashMap<Int, AtomicLong>()
    private val creditFrameCounts = ConcurrentHashMap<Int, AtomicLong>()

    @Volatile
    var eventsChannel: Int? = null
        private set

    init {
        scope.launch { serve() }
    }

    // ---- scripting API -------------------------------------------------------------------------

    /** Payload bytes of CREDIT frames the client sent on [channel]. */
    fun creditReceived(channel: Int): Long = credits[channel]?.get() ?: 0

    /** Number of CREDIT frames the client sent on [channel]. */
    fun creditFrames(channel: Int): Long = creditFrameCounts[channel]?.get() ?: 0

    /** Bytes the fake has actually written to [channel] so far (limited by credit). */
    fun sentOn(channel: Int): Long = streams[channel]?.sent ?: 0

    /** Input (DATA) the client typed on an attach channel. */
    fun inputOf(channel: Int): ByteArray = streams[channel]?.input?.toByteArray() ?: ByteArray(0)

    /** The attach channel opened for [terminal], if any. */
    fun attachChannelOf(terminal: String): Int? = streams.values.firstOrNull { it.terminal == terminal }?.id

    /** Queue stream bytes (credit-limited, chunked). */
    suspend fun streamOutput(
        channel: Int,
        bytes: ByteArray,
    ) {
        val s = streams[channel] ?: error("no stream $channel")
        s.queue.add(Item.Bytes(bytes))
        pump(s)
    }

    /** Close a stream after its queued output is delivered (needs credit, SPEC gotcha #5). [reason] null = empty CLOSE. */
    suspend fun closeStream(
        channel: Int,
        reason: JsonObject? = null,
    ) {
        val s = streams[channel] ?: error("no stream $channel")
        s.queue.add(Item.Close(reason))
        pump(s)
    }

    suspend fun pushEvent(
        type: String,
        terminal: String? = null,
        data: JsonObject = JsonObject(emptyMap()),
        machine: String? = null,
    ): Long {
        val n = seq.incrementAndGet()
        val params =
            buildJsonObject {
                put("seq", n)
                put("type", type)
                terminal?.let { put("terminal", it) }
                machine?.let { put("machine", it) }
                put("data", data)
            }
        pushLine("event", params)
        return n
    }

    suspend fun pushHeartbeat() = pushLine("heartbeat", buildJsonObject { put("seq", seq.get()) })

    /** Skips ahead in `seq` and sends a fresh snapshot, like the real core after the client fell behind. */
    suspend fun pushMissed(skip: Long = 100): Long {
        val n = seq.addAndGet(skip)
        pushLine(
            "missed",
            buildJsonObject {
                put("seq", n)
                put("terminals", JsonArray(terminals.toList()))
            },
        )
        return n
    }

    private suspend fun pushLine(
        method: String,
        params: JsonObject,
    ) {
        val line =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString() + "\n"
        streamOutput(eventsChannel ?: error("not subscribed"), line.toByteArray())
    }

    /** Write raw bytes to the client (e.g. to test garbage or coalesced reads). */
    suspend fun writeRaw(bytes: ByteArray) = writeLock.withLock { server.write(bytes) }

    /** Drop the link: the client sees EOF. */
    suspend fun dropConnection() = server.close()

    // ---- server loop ---------------------------------------------------------------------------

    private class ServerStream(
        val id: Int,
        val terminal: String?,
    ) {
        var credit = WireLimits.WINDOW.toLong()
        var sent = 0L
        val queue = ArrayDeque<Item>()
        val input = java.io.ByteArrayOutputStream()
        val lock = Mutex()
    }

    private sealed interface Item {
        class Bytes(
            var bytes: ByteArray,
        ) : Item

        class Close(
            val reason: JsonObject?,
        ) : Item
    }

    private suspend fun serve() {
        config.banner?.let { server.write(it.toByteArray()) }
        val buf = ByteArray(64 * 1024)
        var pendingPlain = ByteArray(0)
        val decoder = FrameDecoder()
        var framed = false
        while (true) {
            val n = server.read(buf)
            if (n < 0) return
            if (!framed) {
                pendingPlain += buf.copyOf(n)
                val nl = pendingPlain.indexOf('\n'.code.toByte())
                if (nl < 0) continue
                val line = pendingPlain.copyOfRange(0, nl).decodeToString()
                val rest = pendingPlain.copyOfRange(nl + 1, pendingPlain.size)
                framed = handleHello(line)
                if (!framed) return
                decoder.feed(rest)
            } else {
                decoder.feed(buf, 0, n)
            }
            while (true) handleFrame(decoder.next() ?: break)
        }
    }

    private suspend fun handleHello(line: String): Boolean {
        val req = ProtocolJson.parseToJsonElement(line).jsonObject
        val id = req["id"] ?: JsonNull
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        val gen = params["generation"]?.jsonPrimitive?.intOrNull
        if (gen != null && gen != config.generation) {
            writeLine(
                errorBody(
                    id,
                    FakeRpcError(
                        "unsupported_generation",
                        "protocol generation $gen not supported",
                        "this core speaks generation ${config.generation}",
                    ),
                ),
            )
            return false
        }
        val result =
            buildJsonObject {
                put(
                    "core",
                    buildJsonObject {
                        put("id", config.coreId)
                        put("name", config.coreName)
                    },
                )
                put("features", JsonArray(config.features.map(::JsonPrimitive)))
                put("framed", config.framed)
                put("generation", config.generation)
                put("methods", JsonArray(config.methods.map(::JsonPrimitive)))
                put("version", config.version)
            }
        writeLine(
            buildJsonObject {
                put("id", id)
                put("jsonrpc", "2.0")
                put("result", result)
            },
        )
        return config.framed
    }

    private suspend fun writeLine(o: JsonObject) =
        writeLock.withLock { server.write((o.toString() + "\n").toByteArray()) }

    private suspend fun handleFrame(f: Frame) {
        if (f.channel == 0) {
            if (f.kind == FrameKind.DATA) {
                val req = ProtocolJson.parseToJsonElement(f.payload.decodeToString()).jsonObject
                scope.launch { handleRequest(req) }
            }
            return
        }
        streamFrames.add(f)
        val s = streams[f.channel]
        when (f.kind) {
            FrameKind.CREDIT -> {
                val n = FrameCodec.readLe32(f.payload).toLong() and 0xFFFFFFFFL
                credits.getOrPut(f.channel) { AtomicLong() }.addAndGet(n)
                creditFrameCounts.getOrPut(f.channel) { AtomicLong() }.incrementAndGet()
                if (s != null) {
                    s.credit += n
                    pump(s)
                }
            }

            FrameKind.CLOSE -> {
                streams.remove(f.channel)
                writeFrame(FrameCodec.close(f.channel))
            }

            FrameKind.DATA -> {
                if (s != null && s.terminal != null) {
                    s.input.write(f.payload)
                    streamOutput(s.id, f.payload) // echo
                }
            }
        }
    }

    private suspend fun writeFrame(bytes: ByteArray) = writeLock.withLock { server.write(bytes) }

    private suspend fun pump(s: ServerStream) {
        s.lock.withLock {
            while (s.queue.isNotEmpty()) {
                when (val item = s.queue.first()) {
                    is Item.Close -> {
                        s.queue.removeFirst()
                        streams.remove(s.id)
                        writeFrame(FrameCodec.close(s.id, item.reason?.toString()?.toByteArray() ?: ByteArray(0)))
                    }

                    is Item.Bytes -> {
                        if (s.credit <= 0) return
                        val n = minOf(item.bytes.size.toLong(), s.credit, config.streamChunk.toLong()).toInt()
                        writeFrame(FrameCodec.data(s.id, item.bytes, 0, n))
                        s.credit -= n
                        s.sent += n
                        if (n ==
                            item.bytes.size
                        ) {
                            s.queue.removeFirst()
                        } else {
                            item.bytes =
                                item.bytes.copyOfRange(n, item.bytes.size)
                        }
                    }
                }
            }
        }
    }

    private fun errorBody(
        id: JsonElement,
        e: FakeRpcError,
    ) = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", e.code)
                put(
                    "data",
                    buildJsonObject {
                        put("hint", e.hint)
                        put("kind", e.kind)
                    },
                )
                put(
                    "message",
                    e.message ?: e.kind,
                )
            },
        )
        put("id", id)
        put("jsonrpc", "2.0")
    }

    private suspend fun handleRequest(req: JsonObject) {
        val id = req["id"] ?: JsonNull
        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: return
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        calls.add(FakeCall(method, params))
        gates[method]?.await()
        delays[method]?.let { delay(it) }
        val reply: JsonObject =
            try {
                errors[method]?.let { throw it }
                val result: JsonElement
                if (method == "terminal.attach" && handlers[method] == null) {
                    val (channel, body) = prepareAttach(params)
                    result = body
                    if (config.paintBeforeReply) streamOutput(channel, config.firstPaint)
                    writeFrame(FrameCodec.data(0, replyBody(id, result).toString().toByteArray()))
                    if (!config.paintBeforeReply) streamOutput(channel, config.firstPaint)
                    return
                }
                result = (handlers[method] ?: defaultHandler(method)).invoke(params)
                replyBody(id, result)
            } catch (e: FakeRpcError) {
                errorBody(id, e)
            }
        writeFrame(FrameCodec.data(0, reply.toString().toByteArray()))
    }

    private fun replyBody(
        id: JsonElement,
        result: JsonElement,
    ) = buildJsonObject {
        put("id", id)
        put("jsonrpc", "2.0")
        put("result", result)
    }

    private fun prepareAttach(params: JsonObject): Pair<Int, JsonElement> {
        val address =
            params["terminal"]?.jsonPrimitive?.contentOrNull
                ?: throw FakeRpcError("invalid_params", "terminal required", code = -32602)
        val term =
            terminals.firstOrNull { it["address"]?.jsonPrimitive?.contentOrNull == address }
                ?: throw FakeRpcError("not_found", "no such terminal: $address")
        val ch = nextChannel.getAndIncrement()
        streams[ch] = ServerStream(ch, address)
        return ch to
            buildJsonObject {
                put("channel", ch)
                put("terminal", term)
            }
    }

    private fun defaultHandler(method: String): suspend (JsonObject) -> JsonElement =
        when (method) {
            "events.subscribe" -> {
                {
                    val ch = nextChannel.getAndIncrement()
                    streams[ch] = ServerStream(ch, null)
                    eventsChannel = ch
                    buildJsonObject {
                        put("seq", seq.get())
                        put("terminals", JsonArray(terminals.toList()))
                        put("channel", ch)
                    }
                }
            }

            "terminal.list" -> {
                {
                    buildJsonObject {
                        put("terminals", JsonArray(terminals.toList()))
                        put("hidden_finished", 0)
                    }
                }
            }

            "terminal.get" -> {
                { p ->
                    val a = p["terminal"]?.jsonPrimitive?.contentOrNull
                    terminals.firstOrNull { it["address"]?.jsonPrimitive?.contentOrNull == a }
                        ?: throw FakeRpcError("not_found", "no such terminal: $a")
                }
            }

            "doc.get" -> {
                {
                    buildJsonObject {
                        put("value", JsonNull)
                        put("version", 0)
                    }
                }
            }

            else -> {
                { throw FakeRpcError("unknown_method", "unknown method: $method", code = -32601) }
            }
        }

    companion object {
        /** A minimal valid `Terminal` JSON object for scripting. */
        fun terminal(
            address: String,
            name: String? = null,
            state: String = "live",
            cols: Int = 80,
            rows: Int = 24,
        ): JsonObject =
            buildJsonObject {
                put("address", address)
                name?.let { put("name", it) }
                put("labels", JsonObject(emptyMap()))
                put("argv", JsonArray(listOf(JsonPrimitive("sh"))))
                put("cwd", "/tmp")
                put("state", state)
                put("pid", if (state == "live") JsonPrimitive(4242) else JsonNull)
                put("created_ms", 1_700_000_000_000)
                put("screen", "exact")
                put("cols", cols)
                put("rows", rows)
                put("status", JsonNull)
                put("turn", 0)
            }
    }
}
