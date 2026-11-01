package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.model.CoreEndpoint
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.model.TerminalAddress
import com.mtajchert.flocket.core.model.TerminalRecord
import com.mtajchert.flocket.core.model.TerminalTemplate
import com.mtajchert.flocket.core.session.AttachRequest
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.InterruptOutcome
import com.mtajchert.flocket.core.session.RunRequest
import com.mtajchert.flocket.core.session.SendReceipt
import com.mtajchert.flocket.core.session.SessionState
import com.mtajchert.flocket.core.session.TerminalStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
    val calls = mutableListOf<String>()

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

    override suspend fun templates(): AppResult<List<TerminalTemplate>> = templatesHandler()

    override suspend fun readText(
        address: TerminalAddress,
        historyLines: Int,
    ): AppResult<String> = AppResult.Success("")

    override suspend fun attach(
        address: TerminalAddress,
        request: AttachRequest,
    ): AppResult<TerminalStream> = unsupported()
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
}
