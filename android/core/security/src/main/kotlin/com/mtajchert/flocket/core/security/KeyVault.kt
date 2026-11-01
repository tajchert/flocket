package com.mtajchert.flocket.core.security

import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.DeviceKey

/** Device SSH keys, encrypted at rest. Private keys never leave this module except via [loadPrivateKeyPem]. */
interface KeyVault {
    suspend fun generateEd25519(
        alias: String,
        comment: String,
    ): AppResult<DeviceKey>

    /**
     * Fails with `InvalidInput("alias")` if [alias] already exists: a key is never overwritten. Callers pass a
     * unique alias (e.g. UUID based) and keep the user-facing label in [comment] (stored in the public meta and
     * the OpenSSH public key comment).
     *
     * Imports an OpenSSH/PKCS#8/PKCS#1 key; a passphrase is removed (stored key is unencrypted at the SSH level). */
    suspend fun import(
        alias: String,
        pem: CharArray,
        passphrase: CharArray?,
        comment: String = alias,
    ): AppResult<DeviceKey>

    suspend fun list(): AppResult<List<DeviceKey>>

    suspend fun delete(alias: String): AppResult<Unit>

    /** Decrypted unencrypted PEM for `SshCredentials.PrivateKey`. The caller must zero the array. */
    suspend fun loadPrivateKeyPem(alias: String): AppResult<CharArray>
}
