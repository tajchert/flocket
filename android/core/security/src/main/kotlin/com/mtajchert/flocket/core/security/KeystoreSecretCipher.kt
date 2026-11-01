package com.mtajchert.flocket.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key ([alias]), created on first use.
 * No user-authentication requirement (M1). Blob layout: `[ivLength:1][iv][ciphertext+tag]`.
 */
class KeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray =
        guard {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
        }

    override fun decrypt(blob: ByteArray): ByteArray =
        guard {
            val ivLen = blob.firstOrNull()?.toInt() ?: 0
            if (ivLen !in MIN_IV..MAX_IV || blob.size < 1 + ivLen + TAG_BITS / 8) {
                throw SecureStorageException("Sealed blob is malformed")
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 1, ivLen))
            cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
        }

    // Synchronized: two first-use callers racing would each generate a key under [alias], and the
    // blob sealed with the overwritten key could never be decrypted again.
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: SecureStorageException) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecureStorageException("Keystore key invalidated", e)
        } catch (e: GeneralSecurityException) {
            throw SecureStorageException("Keystore operation failed: ${e.javaClass.simpleName}", e)
        } catch (e: RuntimeException) {
            // Keystore daemon problems surface as ProviderException / IllegalStateException.
            throw SecureStorageException("Keystore unavailable: ${e.javaClass.simpleName}", e)
        }

    companion object {
        const val DEFAULT_ALIAS = "flocket.vault.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val MIN_IV = 12
        private const val MAX_IV = 16
    }
}
