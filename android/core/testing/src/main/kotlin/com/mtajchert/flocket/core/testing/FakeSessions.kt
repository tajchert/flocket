package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.AgentMarks
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.LayoutRef
import com.mtajchert.flocket.core.model.PutReceipt
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalRef
import com.mtajchert.flocket.core.model.TerminalTemplate
import com.mtajchert.flocket.core.session.AttachRequest
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.DownloadEvent
import com.mtajchert.flocket.core.session.DownloadResume
import com.mtajchert.flocket.core.session.ExecOutcome
import com.mtajchert.flocket.core.session.InterruptOutcome
import com.mtajchert.flocket.core.session.RunRequest
import com.mtajchert.flocket.core.session.SendReceipt
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.session.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * A [CoreSession] whose [SessionState] you drive from the test or preview: call [update] / [set].
 * [onStart] and [onRetry] let demo wiring script reactions (e.g. go Online after the key is pinned).
 */
class FakeCoreSession(
    override val endpoint: CoreEndpoint,
    initial: SessionState = SessionState(endpoint),
) : CoreSession {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SessionState> = mutable
    var startCount = 0
        private set
    var retryCount = 0
        private set
    var onStart: (FakeCoreSession) -> Unit = {}
    var onRetry: (FakeCoreSession) -> Unit = {}

    fun set(state: SessionState) {
        mutable.value = state
    }

    fun update(transform: (SessionState) -> SessionState) = mutable.update(transform)

    override fun start() {
        startCount++
        onStart(this)
    }

    override suspend fun stop() = Unit

    override fun retryNow() {
        retryCount++
        onRetry(this)
    }

    private fun <T> unsupported(): AppResult<T> = AppResult.Failure(AppError.Unsupported("fake"))

    /** Scripted behaviour for tests. Each handler defaults to `Unsupported`; calls are recorded in [calls]. */
    var runHandler: suspend (RunRequest) -> AppResult<TerminalRecord> = { unsupported() }
    var killHandler: suspend (TerminalAddress) -> AppResult<TerminalRecord> = { unsupported() }
    var removeHandler: suspend (TerminalAddress) -> AppResult<Unit> = { unsupported() }
    var sendHandler: suspend (TerminalAddress, String, Boolean) -> AppResult<SendReceipt> = { _, _, _ -> unsupported() }
    var interruptHandler: suspend (TerminalAddress) -> AppResult<InterruptOutcome> = { unsupported() }
    var templatesHandler: suspend () -> AppResult<List<TerminalTemplate>> = { AppResult.Success(emptyList()) }
    var homeHandler: suspend (String?) -> AppResult<String> = { AppResult.Success("/home/user") }
    var attachHandler: suspend (TerminalAddress, AttachRequest) -> AppResult<TerminalStream> = { _, _ -> unsupported() }
    var renameHandler: suspend (LayoutRef, String) -> AppResult<Unit> = { _, _ -> unsupported() }
    var labelsHandler: suspend (
        TerminalAddress,
        Map<String, String>,
    ) -> AppResult<TerminalRecord> = { _, _ -> unsupported() }

    /** Every `setLabels` call, in order. */
    val labelWrites: MutableList<Pair<TerminalAddress, Map<String, String>>> =
        java.util.Collections.synchronizedList(mutableListOf())
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun run(request: RunRequest): AppResult<TerminalRecord> {
        calls += "run:${request.cwd}:${request.line}:${request.template}:${request.machine}:${request.keep}"
        return runHandler(request)
    }

    override suspend fun kill(address: TerminalAddress): AppResult<TerminalRecord> {
        calls += "kill:${address.value}"
        return killHandler(address)
    }

    override suspend fun remove(address: TerminalAddress): AppResult<Unit> {
        calls += "remove:${address.value}"
        return removeHandler(address)
    }

    override suspend fun sendText(
        address: TerminalAddress,
        text: String,
        force: Boolean,
    ): AppResult<SendReceipt> {
        calls += "send:${address.value}:$text:$force"
        return sendHandler(address, text, force)
    }

    override suspend fun interrupt(address: TerminalAddress): AppResult<InterruptOutcome> {
        calls += "interrupt:${address.value}"
        return interruptHandler(address)
    }

    override suspend fun setLabels(
        address: TerminalAddress,
        labels: Map<String, String>,
    ): AppResult<TerminalRecord> {
        labelWrites += address to labels
        return labelsHandler(address, labels)
    }

    override suspend fun templates(): AppResult<List<TerminalTemplate>> = templatesHandler()

    override suspend fun homeDirectory(machine: String?): AppResult<String> {
        calls += "home:$machine"
        return homeHandler(machine)
    }

    override suspend fun renameInLayout(
        ref: LayoutRef,
        name: String,
    ): AppResult<Unit> {
        calls += "rename:${ref.machine}:${ref.spaceId}:${ref.tabId}:$name"
        return renameHandler(ref, name)
    }

    override suspend fun readText(
        address: TerminalAddress,
        historyLines: Int,
    ): AppResult<String> = AppResult.Success("")

    override suspend fun attach(
        address: TerminalAddress,
        request: AttachRequest,
    ): AppResult<TerminalStream> {
        calls += "attach:${address.value}:${request.mode}"
        return attachHandler(address, request)
    }

    // ---- file transfers: scripted like the handlers above; every call is recorded in [transferCalls] ----

    var putHandler: suspend (TerminalAddress, String, ByteArray) -> AppResult<PutReceipt> = { a, name, bytes ->
        AppResult.Success(PutReceipt("/fake/files/${a.value}/$name", bytes.size.toLong()))
    }
    var writeHandler: suspend (String?, String, ByteArray) -> AppResult<Unit> = { _, _, _ -> AppResult.Success(Unit) }
    var readHandler: suspend (String?, String) -> AppResult<ByteArray> = { _, _ -> AppResult.Success(ByteArray(0)) }
    var execHandler: suspend (String?, List<String>, Boolean) -> AppResult<ExecOutcome> = { _, _, _ ->
        AppResult.Success(ExecOutcome(0, ByteArray(0), "", false))
    }

    /** Scripted `download`: write bytes to `target` and emit events. The default fails with `Unsupported("download")`. */
    var downloadHandler: (String?, String, java.io.File, DownloadResume?) -> Flow<DownloadEvent> = { _, _, _, _ ->
        flowOf(DownloadEvent.Failed(AppError.Unsupported("download"), null))
    }
    private val transferLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** `put:<address>:<name>:<size>`, `write:<machine>:<path>:<size>`, `read:<machine>:<path>`, `download:<machine>:<path>:<resume given>`, `exec:<machine>:<argv joined by space>:<mutating>`. */
    val transferCalls: List<String> get() = synchronized(transferLog) { transferLog.toList() }

    override suspend fun putFile(
        address: TerminalAddress,
        name: String,
        bytes: ByteArray,
    ): AppResult<PutReceipt> {
        transferLog += "put:${address.value}:$name:${bytes.size}"
        return putHandler(address, name, bytes)
    }

    override suspend fun writeFile(
        machine: String?,
        path: String,
        bytes: ByteArray,
    ): AppResult<Unit> {
        transferLog += "write:$machine:$path:${bytes.size}"
        return writeHandler(machine, path, bytes)
    }

    override suspend fun readFile(
        machine: String?,
        path: String,
    ): AppResult<ByteArray> {
        transferLog += "read:$machine:$path"
        return readHandler(machine, path)
    }

    override fun download(
        machine: String?,
        path: String,
        target: java.io.File,
        resume: DownloadResume?,
    ): Flow<DownloadEvent> {
        transferLog += "download:$machine:$path:${resume != null}"
        return downloadHandler(machine, path, target, resume)
    }

    override suspend fun exec(
        machine: String?,
        argv: List<String>,
        mutating: Boolean,
    ): AppResult<ExecOutcome> {
        transferLog += "exec:$machine:${argv.joinToString(" ")}:$mutating"
        return execHandler(machine, argv, mutating)
    }
}

/**
 * [ConnectionManager] over a [HostRepository]: one [FakeCoreSession] per host, created lazily with
 * [initialState]. Drive a host with `manager.fakeSession(id)!!.update { ... }`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeConnectionManager(
    hostRepository: HostRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val initialState: (Host) -> SessionState = { SessionState(it.endpoint) },
    private val configure: (FakeCoreSession) -> Unit = {},
) : ConnectionManager {
    private val sessions = LinkedHashMap<HostId, FakeCoreSession>()
    private val providedPasswords = MutableStateFlow(emptySet<HostId>())
    val passwordProvidedFor: Set<HostId> get() = providedPasswords.value

    override val overview: StateFlow<List<HostOverview>> =
        hostRepository.hosts
            .flatMapLatest { hosts ->
                if (hosts.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(hosts.map { host -> sessionFor(host).state.map { HostOverview(host, it) } }) { it.toList() }
                }
            }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    @Synchronized
    private fun sessionFor(host: Host): FakeCoreSession =
        sessions.getOrPut(host.id) { FakeCoreSession(host.endpoint, initialState(host)).also(configure) }

    fun fakeSession(hostId: HostId): FakeCoreSession? = sessions[hostId]

    override fun session(hostId: HostId): CoreSession? = sessions[hostId]

    override fun providePassword(
        hostId: HostId,
        password: CharArray,
    ) {
        password.fill('\u0000')
        providedPasswords.update { it + hostId }
        sessions[hostId]?.retryNow()
    }

    private val connectRequested = MutableStateFlow(emptyList<HostId>())

    /** Hosts passed to [connect], in order. */
    val connectRequests: List<HostId> get() = connectRequested.value

    override fun connect(hostId: HostId) = connectRequested.update { it + hostId }

    private val held = MutableStateFlow(emptySet<Any>())

    /** Tokens of terminal screens currently held for background keep-alive. */
    val heldTerminals: Set<Any> get() = held.value

    override fun holdTerminal(token: Any) = held.update { it + token }

    override fun releaseTerminal(token: Any) = held.update { it - token }
}

/** [AgentMarks] that records calls; results are scripted with [result]. */
class FakeAgentMarks(
    var supported: Boolean = true,
) : AgentMarks {
    var result: AppResult<Unit> = AppResult.Success(Unit)
    val seen: MutableList<TerminalRef> = java.util.Collections.synchronizedList(mutableListOf())
    val unread: MutableList<TerminalRef> = java.util.Collections.synchronizedList(mutableListOf())
    val pinned: MutableList<Pair<TerminalRef, Boolean>> = java.util.Collections.synchronizedList(mutableListOf())

    override fun supportsLabels(hostId: HostId): Boolean = supported

    override suspend fun markSeen(ref: TerminalRef): AppResult<Unit> {
        seen += ref
        return result
    }

    override suspend fun markUnread(ref: TerminalRef): AppResult<Unit> {
        unread += ref
        return result
    }

    override suspend fun setPinned(
        ref: TerminalRef,
        pinned: Boolean,
    ): AppResult<Unit> {
        this.pinned += ref to pinned
        return result
    }
}
