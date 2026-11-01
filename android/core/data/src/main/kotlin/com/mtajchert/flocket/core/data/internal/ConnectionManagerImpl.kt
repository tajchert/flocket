package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.data.AppForeground
import com.mtajchert.flocket.core.data.AutoConnectSettings
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.KeepAliveService
import com.mtajchert.flocket.core.data.NetworkMonitor
import com.mtajchert.flocket.core.data.NetworkState
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.session.CoreSession
import com.mtajchert.flocket.core.session.CoreSessionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns one [CoreSession] per configured host.
 *
 * - Hosts added/removed/edited create/stop/recreate sessions. Only fields that change *where or as whom* we
 *   connect (hostname, port, username, auth, hc session) recreate; label/hcPath edits just refresh [overview]
 *   (the transport factory reads the host fresh on every connect, so they apply on the next attempt).
 * - Foreground starts every session. Going to the background (ADR-0006): with the user's keep-alive setting
 *   on and at least one terminal screen held ([holdTerminal]) the [keepAlive] service starts and sessions stay up until the user returns, the
 *   service ends ([keepAliveEnded]), the last terminal is released, or [keepAliveWindowMs] passes (just under
 *   the platform's ~3 min `shortService` limit, so we stop before `onTimeout`). Without a terminal, or when
 *   the platform refuses the service, sessions stop at once: Android 15+ cuts a background app's network
 *   ~5 s later anyway, so waiting would only turn a clean detach into a dropped link.
 * - Auto-connect: a host whose effective `autoConnect` is off keeps its session Idle until [connect] is called
 *   (the user opened the host or one of its terminals, pulled to refresh, tapped Connect). Such a host then
 *   reconnects like any other whenever the app returns to the foreground during this process.
 * - Regaining or changing the network retries sessions that are waiting in backoff.
 * - Each host's work runs in its own child job: one slow or failing host never blocks another.
 */
internal class ConnectionManagerImpl(
    private val hostRepository: HostRepository,
    private val sessionFactory: CoreSessionFactory,
    private val connector: HostConnector,
    foreground: AppForeground,
    network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val keepAlive: KeepAliveService = NoKeepAlive,
    private val keepAliveWindowMs: Long = KEEP_ALIVE_WINDOW_MS,
    /** The user's app-level "Keep connected in background" setting; off means detach at once. */
    keepAliveEnabled: Flow<Boolean> = flowOf(true),
    /** Effective per-host auto-connect (app default + host overrides). */
    autoConnect: Flow<AutoConnectSettings> = flowOf(AutoConnectSettings()),
    private val logger: Logger = Logger.None,
) : ConnectionManager {
    private class Entry(
        val host: Host,
        val session: CoreSession,
    )

    private val lock = Any()
    private var entries: Map<HostId, Entry> = emptyMap()
    private var active = false
    private var foreground = false
    private val terminals = LinkedHashSet<Any>()
    private var keepAliveRunning = false

    @Volatile private var keepAliveAllowed = true
    private var autoConnectSettings = AutoConnectSettings()
    private val settingsReady = CompletableDeferred<Unit>()

    /** Hosts the user asked to connect although their auto-connect is off. */
    private val manual = HashSet<HostId>()
    private var windowJob: Job? = null
    private var lastNetwork: NetworkState? = null

    private val roster = MutableStateFlow<List<Entry>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    override val overview: StateFlow<List<HostOverview>> =
        roster
            .flatMapLatest { list ->
                if (list.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(list.map { it.session.state }) { states ->
                        list.mapIndexed { i, e -> HostOverview(e.host, states[i]) }
                    }
                }
            }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch { hostRepository.hosts.collect { reconcile(it) } }
        scope.launch {
            // Don't start anything before we know which hosts opted out of auto-connect.
            settingsReady.await()
            foreground.isForeground.distinctUntilChanged().collect { onForeground(it) }
        }
        scope.launch { network.state.distinctUntilChanged().collect { onNetwork(it) } }
        scope.launch { keepAliveEnabled.collect { keepAliveAllowed = it } }
        scope.launch { autoConnect.collect { onAutoConnect(it) } }
    }

    // Callers hold [lock].
    private fun wants(id: HostId) = autoConnectSettings.isEnabled(id) || id in manual

    private fun onAutoConnect(settings: AutoConnectSettings) {
        val toStart =
            synchronized(lock) {
                autoConnectSettings = settings
                if (active) entries.filter { wants(it.key) }.values.map { it.session } else emptyList()
            }
        settingsReady.complete(Unit)
        // A host that just opted in starts now; one that opted out keeps running until the next detach.
        toStart.forEach { s -> safely("start") { s.start() } }
    }

    override fun connect(hostId: HostId) {
        val session =
            synchronized(lock) {
                manual += hostId
                if (active) entries[hostId]?.session else null
            }
        session?.let { s -> safely("start") { s.start() } }
    }

    override fun session(hostId: HostId): CoreSession? = synchronized(lock) { entries[hostId]?.session }

    override fun providePassword(
        hostId: HostId,
        password: CharArray,
    ) {
        val session = synchronized(lock) { entries[hostId]?.session } ?: return
        connector.setPassword(hostId, password)
        session.retryNow()
    }

    private fun reconcile(hosts: List<Host>) {
        val toStop = mutableListOf<CoreSession>()
        val toStart = mutableListOf<CoreSession>()
        val ordered: List<Entry>
        synchronized(lock) {
            val old = entries
            val next = LinkedHashMap<HostId, Entry>()
            for (host in hosts) {
                val prev = old[host.id]
                next[host.id] =
                    when {
                        prev == null -> {
                            newEntry(host).also { if (active && wants(host.id)) toStart += it.session }
                        }

                        prev.host == host -> {
                            prev
                        }

                        needsNewSession(prev.host, host) -> {
                            toStop += prev.session
                            newEntry(host).also { if (active && wants(host.id)) toStart += it.session }
                        }

                        else -> {
                            Entry(host, prev.session)
                        }
                    }
            }
            for ((id, e) in old) {
                if (id !in next) {
                    toStop += e.session
                    connector.clearPassword(id)
                    manual -= id
                }
            }
            entries = next
            ordered = next.values.toList()
        }
        roster.value = ordered
        toStop.forEach { s -> scope.launch { safely("stop") { s.stop() } } }
        toStart.forEach { s -> safely("start") { s.start() } }
    }

    private fun newEntry(host: Host): Entry {
        val id = host.id
        val session = sessionFactory.create(host.endpoint) { connector.open(id) }
        return Entry(host, session)
    }

    private fun needsNewSession(
        old: Host,
        new: Host,
    ): Boolean =
        old.hostname != new.hostname ||
            old.port != new.port ||
            old.username != new.username ||
            old.auth != new.auth ||
            old.session != new.session

    override fun holdTerminal(token: Any) {
        synchronized(lock) { terminals += token }
    }

    override fun releaseTerminal(token: Any) {
        val expire =
            synchronized(lock) {
                terminals -= token
                keepAliveRunning && terminals.isEmpty()
            }
        if (expire) endKeepAlive("last terminal closed")
    }

    override fun keepAliveEnded() {
        val expire = synchronized(lock) { keepAliveRunning || (!foreground && active) }
        if (expire) endKeepAlive("service ended")
    }

    private fun onForeground(isForeground: Boolean) {
        val sessions: List<CoreSession>
        val stopService: Boolean
        val keepFor: Int
        synchronized(lock) {
            foreground = isForeground
            windowJob?.cancel()
            windowJob = null
            if (isForeground) {
                stopService = keepAliveRunning
                keepAliveRunning = false
                active = true
                sessions = entries.filter { wants(it.key) }.values.map { it.session }
                keepFor = 0
            } else {
                stopService = false
                sessions = emptyList()
                // Never started (process start) or already detached: nothing to keep or stop.
                keepFor =
                    when {
                        !active -> -1
                        !keepAliveAllowed -> 0
                        else -> terminals.size
                    }
            }
        }
        if (isForeground) {
            if (stopService) safely("keep-alive stop") { keepAlive.stop() }
            sessions.forEach { s -> safely("start") { s.start() } }
            return
        }
        when {
            keepFor < 0 -> {
                Unit
            }

            keepFor == 0 -> {
                detachAll("background, no terminal open or keep-alive off")
            }

            else -> {
                val started =
                    try {
                        keepAlive.start(keepFor)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(TAG, { "keep-alive start failed" }, e)
                        false
                    }
                if (!started) {
                    detachAll("background, keep-alive not allowed")
                    return
                }
                synchronized(lock) {
                    // The user may have come back while start() ran.
                    if (foreground) return@synchronized
                    keepAliveRunning = true
                    windowJob =
                        scope.launch {
                            delay(keepAliveWindowMs)
                            endKeepAlive("window over")
                        }
                }
                if (synchronized(lock) { foreground }) safely("keep-alive stop") { keepAlive.stop() }
            }
        }
    }

    /** Ends the background window: stop the service and detach every session cleanly. */
    private fun endKeepAlive(why: String) {
        val stopService =
            synchronized(lock) {
                if (foreground) return
                windowJob?.cancel()
                windowJob = null
                keepAliveRunning.also { keepAliveRunning = false }
            }
        if (stopService) safely("keep-alive stop") { keepAlive.stop() }
        detachAll(why)
    }

    private fun detachAll(why: String) {
        val toStop =
            synchronized(lock) {
                if (foreground || !active) return
                active = false
                entries.values.map { it.session }
            }
        logger.info(TAG, { "detaching ${toStop.size} session(s): $why" })
        toStop.forEach { s -> scope.launch { safely("stop") { s.stop() } } }
    }

    private fun onNetwork(state: NetworkState) {
        val previous: NetworkState?
        val sessions: List<CoreSession>
        synchronized(lock) {
            previous = lastNetwork
            lastNetwork = state
            sessions = if (active) entries.values.map { it.session } else emptyList()
        }
        // Retry only when connectivity came back or the network changed. Losing it must not spin: the
        // sessions back off on their own.
        val regained = !previous?.isAvailable.orFalse() || previous?.networkId != state.networkId
        if (previous == null || !state.isAvailable || !regained) return
        sessions.forEach { s ->
            if (s.state.value.connection is ConnectionState.Reconnecting) safely("retry") { s.retryNow() }
        }
    }

    private fun Boolean?.orFalse() = this ?: false

    private inline fun safely(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, { "session $what failed" }, e)
        }
    }

    private object NoKeepAlive : KeepAliveService {
        override fun start(terminals: Int) = false

        override fun stop() = Unit
    }

    companion object {
        /** Just under the ~3 min `shortService` timeout, so we stop first instead of hitting `onTimeout`. */
        const val KEEP_ALIVE_WINDOW_MS = 170_000L
        private const val TAG = "ConnectionManager"
    }
}
