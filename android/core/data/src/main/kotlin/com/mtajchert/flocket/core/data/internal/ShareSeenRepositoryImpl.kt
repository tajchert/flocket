package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.data.ShareSeenRepository
import com.mtajchert.flocket.core.datastore.ShareSeenDataSource
import com.mtajchert.flocket.core.model.AgentShare
import com.mtajchert.flocket.core.model.HostId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/** Never throws: a failing store reads as "nothing seen" and writes are dropped (the card just shows again). */
internal class ShareSeenRepositoryImpl(
    private val source: ShareSeenDataSource,
    private val logger: Logger = Logger.None,
) : ShareSeenRepository {
    override val seen: Flow<Set<String>> =
        source.seen.catch { e ->
            if (e is CancellationException) throw e
            logFailure("read", e)
            emit(emptySet())
        }

    override suspend fun markSeen(ids: Collection<String>) {
        guarded("write", Unit) { source.markSeen(ids) }
    }

    /** A failed first read returns false and hides nothing. */
    override suspend fun firstRead(
        hostId: HostId,
        machine: String?,
        shares: List<AgentShare>,
        nowEpochSeconds: Long,
    ): Boolean =
        guarded("firstRead", false) {
            source.firstRead(
                sourceKey = "${hostId.value}|${machine.orEmpty()}",
                entries = shares.filter { it.machine == machine }.map { it.id to it.atEpochSeconds },
                nowEpochSeconds = nowEpochSeconds,
            )
        }

    private suspend fun <T> guarded(
        what: String,
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(what, e)
            fallback
        }

    // The error kind only: no ids, no paths.
    private fun logFailure(
        what: String,
        e: Throwable,
    ) = logger.warn(TAG, { "share seen store $what failed: ${e::class.simpleName}" })

    private companion object {
        const val TAG = "ShareSeen"
    }
}
