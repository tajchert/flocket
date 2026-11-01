package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.data.ShareSeenRepository
import com.mtajchert.flocket.core.datastore.ShareSeenDataSource
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import kotlinx.coroutines.flow.Flow

internal class ShareSeenRepositoryImpl(
    private val source: ShareSeenDataSource,
) : ShareSeenRepository {
    override val seen: Flow<Set<String>> = source.seen

    override suspend fun markSeen(ids: Collection<String>) = source.markSeen(ids)

    override suspend fun firstRead(
        hostId: HostId,
        machine: String?,
        shares: List<AgentShare>,
        nowEpochSeconds: Long,
    ): Boolean =
        source.firstRead(
            sourceKey = "${hostId.value}|${machine.orEmpty()}",
            entries = shares.filter { it.machine == machine }.map { it.id to it.atEpochSeconds },
            nowEpochSeconds = nowEpochSeconds,
        )
}
