package com.mtajchert.flocket.core.testing

import com.mtajchert.flocket.core.data.ShareSeenRepository
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [ShareSeenRepository] with the real 10 minute rule and no id cap. Share one instance to simulate recreation. */
class FakeShareSeenRepository(
    initial: Set<String> = emptySet(),
) : ShareSeenRepository {
    private val ids = MutableStateFlow(initial)
    private val sources = mutableSetOf<Pair<HostId, String?>>()
    override val seen: Flow<Set<String>> = ids

    override suspend fun markSeen(ids: Collection<String>) {
        this.ids.update { it + ids }
    }

    override suspend fun firstRead(
        hostId: HostId,
        machine: String?,
        shares: List<AgentShare>,
        nowEpochSeconds: Long,
    ): Boolean {
        if (!sources.add(hostId to machine)) return false
        ids.update { current ->
            current + shares.filter { it.machine == machine && it.atEpochSeconds < nowEpochSeconds - 600 }.map { it.id }
        }
        return true
    }
}
