package com.mtajchert.flocket.core.security

/** Failure of the underlying secret store (missing/invalidated Keystore key, provider error). */
class SecureStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Seals small secrets with a key that never leaves secure hardware/OS storage. The Android
 * implementation is [KeystoreSecretCipher]; tests use an in-memory fake.
 */
interface SecretCipher {
    /** Returns a self-contained blob (IV + ciphertext + tag). */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Inverse of [encrypt]. */
    fun decrypt(blob: ByteArray): ByteArray
}
