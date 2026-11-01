package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.appResult
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.database.PinnedHostKeyDao
import com.mtajchert.flocket.core.database.PinnedHostKeyEntity
import com.mtajchert.flocket.core.model.PinnedHostKey

internal class HostTrustRepositoryImpl(
    private val dao: PinnedHostKeyDao,
) : HostTrustRepository {
    override suspend fun pinned(
        hostname: String,
        port: Int,
    ): PinnedHostKey? =
        dao.get(hostname, port)?.let {
            PinnedHostKey(it.hostname, it.port, it.keyType, it.fingerprint, it.pinnedAt)
        }

    override suspend fun pin(key: PinnedHostKey): AppResult<Unit> {
        when {
            key.hostname.isBlank() -> return AppResult.Failure(invalid("hostname", "must not be blank"))

            key.port !in 1..65535 -> return AppResult.Failure(invalid("port", "must be between 1 and 65535"))

            !key.fingerprint.startsWith(
                "SHA256:",
            ) -> return AppResult.Failure(invalid("fingerprint", "expected SHA256:<base64>"))

            key.keyType.isBlank() -> return AppResult.Failure(invalid("keyType", "must not be blank"))
        }
        return appResult {
            dao.upsert(PinnedHostKeyEntity(key.hostname, key.port, key.keyType, key.fingerprint, key.pinnedAtMs))
        }
    }

    override suspend fun forget(
        hostname: String,
        port: Int,
    ): AppResult<Unit> = appResult { dao.delete(hostname, port) }
}
