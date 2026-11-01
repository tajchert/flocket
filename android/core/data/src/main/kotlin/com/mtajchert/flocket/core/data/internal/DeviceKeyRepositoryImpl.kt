package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.common.result.getOrNull
import com.mtajchert.flocket.core.common.result.onSuccess
import com.mtajchert.flocket.core.data.DeviceKeyRepository
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.security.KeyVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Wraps [KeyVault]. The vault alias is a fresh UUID so keys never collide or overwrite each other; the
 * user's [label] is stored as the key comment (visible in the public key and in the vault's meta).
 */
internal class DeviceKeyRepositoryImpl(
    private val vault: KeyVault,
    private val newAlias: () -> String = { "key-" + UUID.randomUUID() },
) : DeviceKeyRepository {
    private val version = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val keys: Flow<List<DeviceKey>> = version.mapLatest { vault.list().getOrNull().orEmpty() }

    override suspend fun generate(label: String): AppResult<DeviceKey> {
        val name = label.trim()
        if (name.isEmpty()) return AppResult.Failure(invalid("label", "must not be blank"))
        return vault.generateEd25519(newAlias(), name).onSuccess { version.update { it + 1 } }
    }

    override suspend fun import(
        label: String,
        privateKeyPem: CharArray,
        passphrase: CharArray?,
    ): AppResult<DeviceKey> {
        val name = label.trim()
        if (name.isEmpty()) return AppResult.Failure(invalid("label", "must not be blank"))
        return vault.import(newAlias(), privateKeyPem, passphrase, name).onSuccess { version.update { it + 1 } }
    }

    override suspend fun delete(alias: String): AppResult<Unit> = vault.delete(alias).onSuccess { version.update { it + 1 } }
}
