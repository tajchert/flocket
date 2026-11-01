package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.data.AppForeground
import com.mtajchert.flocket.core.data.ConnectionManager
import com.mtajchert.flocket.core.data.CoreSessionFactory
import com.mtajchert.flocket.core.data.HostOverview
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.NetworkMonitor
import com.mtajchert.flocket.core.data.NetworkState
import com.mtajchert.flocket.core.model.ConnectionState
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.session.CoreSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * - Foreground starts every session; going to the background stops them after [gracePeriodMs] unless the
 *   app returns first (screen lock + quick unlock must not reconnect).
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
    private val gracePeriodMs: Long = BACKGROUND_GRACE_MS,
    private val logger: Logger = Logger.None,
) : ConnectionManager {
    private class Entry(
        val host: Host,
        val session: CoreSession,
    )

    private val lock = Any()
    private var entries: Map<HostId, Entry> = emptyMap()
    private var active = false
    private var graceJob: Job? = null
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
        scope.launch { foreground.isForeground.distinctUntilChanged().collect { onForeground(it) } }
        scope.launch { network.state.distinctUntilChanged().collect { onNetwork(it) } }
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
                        prev == null -> newEntry(host).also { if (active) toStart += it.session }
                        prev.host == host -> prev
                        needsNewSession(prev.host, host) -> {
                            toStop += prev.session
                            newEntry(host).also { if (active) toStart += it.session }
                        }
                        else -> Entry(host, prev.session)
                    }
            }
            for ((id, e) in old) {
                if (id !in next) {
                    toStop += e.session
                    connector.clearPassword(id)
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

    private fun onForeground(isForeground: Boolean) {
        val sessions: List<CoreSession>
        synchronized(lock) {
            graceJob?.cancel()
            graceJob = null
            if (isForeground) {
                active = true
                sessions = entries.values.map { it.session }
            } else {
                sessions = emptyList()
                graceJob =
                    scope.launch {
                        delay(gracePeriodMs)
                        val toStop =
                            synchronized(lock) {
                                active = false
                                graceJob = null
                                entries.values.map { it.session }
                            }
                        toStop.forEach { s -> launch { safely("stop") { s.stop() } } }
                    }
            }
        }
        sessions.forEach { s -> safely("start") { s.start() } }
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

    companion object {
        const val BACKGROUND_GRACE_MS = 60_000L
        private const val TAG = "ConnectionManager"
    }
}
