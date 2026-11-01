package com.mtajchert.flocket.core.data

import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import kotlinx.coroutines.flow.Flow

/** Which agent shares the user has already seen (card dismissed or opened). Keyed by share id alone. */
interface ShareSeenRepository {
    val seen: Flow<Set<String>>

    suspend fun markSeen(ids: Collection<String>)

    /**
     * First read of (host, machine): silently mark entries older than 10 min seen. Returns true if this was the
     * first read. Entries of [shares] belonging to other machines are ignored by the caller's grouping.
     */
    suspend fun firstRead(
        hostId: HostId,
        machine: String?,
        shares: List<AgentShare>,
        nowEpochSeconds: Long,
    ): Boolean
}
