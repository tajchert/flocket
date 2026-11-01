package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.appResult
import com.mtajchert.flocket.core.common.time.Clock
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.database.HostDao
import com.mtajchert.flocket.core.database.HostEntity
import com.mtajchert.flocket.core.database.PinnedHostKeyDao
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Persists the discovered `hc` path (see ConnectionManager); not part of the public repository contract. */
internal interface HcPathCache {
    suspend fun cacheHcPath(
        id: HostId,
        path: String,
    )
}

internal class HostRepositoryImpl(
    private val dao: HostDao,
    private val pins: PinnedHostKeyDao,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : HostRepository,
    HcPathCache {
    override val hosts: Flow<List<Host>> = dao.observeAll().map { list -> list.map { it.toHost() } }

    override suspend fun host(id: HostId): Host? = dao.get(id.value)?.toHost()

    override suspend fun save(draft: HostDraft): AppResult<Host> {
        val label = draft.label.trim()
        val hostname = draft.hostname.trim()
        val username = draft.username.trim()
        val hcPath = draft.hcPath?.trim()?.ifEmpty { null }
        val session = draft.session?.trim()?.ifEmpty { null }
        validateHost(label, hostname, draft.port, username, draft.auth, hcPath, session)?.let {
            return AppResult.Failure(it)
        }
        return appResult {
            val existing = draft.id?.let { dao.get(it.value) }
            if (draft.id != null && existing == null) {
                return AppResult.Failure(AppError.InvalidInput("id", "host no longer exists"))
            }
            val entity =
                HostEntity(
                    id = existing?.id ?: newId(),
                    label = label,
                    hostname = hostname,
                    port = draft.port,
                    username = username,
                    authType = if (draft.auth is HostAuth.Key) HostEntity.AUTH_KEY else HostEntity.AUTH_PASSWORD,
                    keyAlias = (draft.auth as? HostAuth.Key)?.keyAlias,
                    hcPath = hcPath,
                    session = session,
                    sortOrder = existing?.sortOrder ?: (dao.maxSortOrder() + 1),
                    createdAt = existing?.createdAt ?: clock.nowMillis(),
                )
            dao.upsert(entity)
            entity.toHost()
        }
    }

    override suspend fun delete(id: HostId): AppResult<Unit> =
        appResult {
            val entity = dao.get(id.value) ?: return@appResult
            dao.delete(id.value)
            // The pin belongs to hostname:port; drop it only when no other host still uses that address.
            val shared = dao.getAll().any { it.hostname == entity.hostname && it.port == entity.port }
            if (!shared) pins.delete(entity.hostname, entity.port)
        }

    override suspend fun cacheHcPath(
        id: HostId,
        path: String,
    ) = dao.updateHcPath(id.value, path)
}

internal fun HostEntity.toHost() =
    Host(
        id = HostId(id),
        label = label,
        hostname = hostname,
        port = port,
        username = username,
        auth =
            if (authType == HostEntity.AUTH_KEY && keyAlias != null) {
                HostAuth.Key(keyAlias!!)
            } else {
                HostAuth.Password
            },
        hcPath = hcPath,
        session = session,
    )
