package com.mtajchert.flocket.core.security

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class KeystoreKeyVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeCipher(
        private val failing: Boolean = false,
    ) : SecretCipher {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun encrypt(plaintext: ByteArray): ByteArray {
            if (failing) throw SecureStorageException("invalidated")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plaintext)
        }

        override fun decrypt(blob: ByteArray): ByteArray {
            if (failing) throw SecureStorageException("invalidated")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            return c.doFinal(blob, 12, blob.size - 12)
        }
    }

    private fun vault(cipher: SecretCipher = FakeCipher()) = KeystoreKeyVault(cipher, tmp.root, nowMs = { 42L })

    @Test
    fun `generate stores ciphertext only and round trips the pem`() =
        runBlocking {
            val v = vault()
            val key = (v.generateEd25519("main", "me@phone") as AppResult.Success).value
            assertEquals("main", key.alias)
            assertEquals(42L, key.createdAtMs)
            assertEquals("ssh-ed25519", key.keyType)
            assertTrue(key.fingerprint.startsWith("SHA256:"))
            val pem = (v.loadPrivateKeyPem("main") as AppResult.Success).value
            assertTrue(String(pem).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
            val onDisk = tmp.root.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertFalse(onDisk.contains("BEGIN OPENSSH"))
            assertFalse(onDisk.contains(String(pem).lines()[1]))
            assertEquals(listOf(key), (v.list() as AppResult.Success).value)
        }

    @Test
    fun `import accepts a valid key and rejects garbage`() =
        runBlocking {
            val v = vault()
            val src =
                com.mtajchert.flocket.core.ssh.SshKeys
                    .generateEd25519("x")
            val k = (v.import("imp", src.privateKeyPem.copyOf(), null) as AppResult.Success).value
            assertEquals(src.fingerprintSha256, k.fingerprint)
            val bad = v.import("bad", "nope".toCharArray(), null)
            assertTrue((bad as AppResult.Failure).error is AppError.InvalidInput)
            assertEquals(1, (v.list() as AppResult.Success).value.size)
        }

    @Test
    fun `delete removes key and missing alias is an error`() =
        runBlocking {
            val v = vault()
            v.generateEd25519("a", "c")
            v.delete("a")
            assertTrue((v.list() as AppResult.Success).value.isEmpty())
            assertTrue((v.loadPrivateKeyPem("a") as AppResult.Failure).error is AppError.InvalidInput)
        }

    @Test
    fun `keystore failure maps to SecureStorageUnavailable`() =
        runBlocking {
            val r = vault(FakeCipher(failing = true)).generateEd25519("a", "c")
            assertTrue((r as AppResult.Failure).error is AppError.SecureStorageUnavailable)
            assertTrue(tmp.root.listFiles().isNullOrEmpty())
        }

    @Test
    fun `malformed sealed blob is rejected before touching the keystore`() {
        for (blob in listOf(ByteArray(0), byteArrayOf(-1), byteArrayOf(12) + ByteArray(12))) {
            try {
                KeystoreSecretCipher().decrypt(blob)
                org.junit.Assert.fail("expected SecureStorageException")
            } catch (_: SecureStorageException) {
            }
        }
    }
}
