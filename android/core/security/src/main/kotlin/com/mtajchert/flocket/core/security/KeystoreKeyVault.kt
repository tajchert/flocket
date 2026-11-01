package com.mtajchert.flocket.core.security

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.model.DeviceKey
import com.mtajchert.flocket.core.ssh.KeyMaterial
import com.mtajchert.flocket.core.ssh.SshKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.CharBuffer
import java.util.Base64
import java.util.Properties
import kotlin.coroutines.cancellation.CancellationException

/**
 * [KeyVault] storing per-alias files in app-private [dir]: `<id>.enc` (private PEM sealed by
 * [cipher]) and `<id>.meta` (public facts only). Plaintext private keys are never written.
 */
class KeystoreKeyVault(
    private val cipher: SecretCipher,
    private val dir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : KeyVault {
    private val lock = Any()

    override suspend fun generateEd25519(
        alias: String,
        comment: String,
    ): AppResult<DeviceKey> = store(alias) { SshKeys.generateEd25519(comment) }

    override suspend fun import(
        alias: String,
        pem: CharArray,
        passphrase: CharArray?,
    ): AppResult<DeviceKey> =
        store(alias) {
            when (val r = SshKeys.import(pem, passphrase, comment = alias)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@store r
            }
        }

    override suspend fun list(): AppResult<List<DeviceKey>> =
        io {
            synchronized(lock) {
                val files = dir.listFiles { f -> f.name.endsWith(META) }.orEmpty()
                AppResult.Success(files.mapNotNull { readMeta(it) }.sortedBy { it.createdAtMs })
            }
        }

    override suspend fun delete(alias: String): AppResult<Unit> =
        io {
            synchronized(lock) {
                File(dir, fileId(alias) + ENC).delete()
                File(dir, fileId(alias) + META).delete()
                AppResult.Success(Unit)
            }
        }

    override suspend fun loadPrivateKeyPem(alias: String): AppResult<CharArray> =
        io {
            val file = File(dir, fileId(alias) + ENC)
            if (!file.exists()) {
                return@io AppResult.Failure(AppError.InvalidInput("alias", "no such key"))
            }
            val bytes = cipher.decrypt(file.readBytes())
            val cb = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
            try {
                AppResult.Success(CharArray(cb.remaining()).also { cb.get(it) })
            } finally {
                bytes.fill(0)
                if (cb.hasArray()) cb.array().fill('\u0000')
            }
        }

    private suspend fun store(
        alias: String,
        produce: () -> Any,
    ): AppResult<DeviceKey> =
        io {
            if (alias.isBlank()) return@io AppResult.Failure(AppError.InvalidInput("alias", "must not be blank"))
            val produced = produce()
            if (produced is AppResult.Failure) return@io produced
            val material = produced as KeyMaterial
            val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(material.privateKeyPem))
            val plain = ByteArray(encoded.remaining()).also { encoded.get(it) }
            if (encoded.hasArray()) encoded.array().fill(0)
            try {
                val sealed = cipher.encrypt(plain)
                val key =
                    DeviceKey(alias, material.publicKeyOpenSsh, material.fingerprintSha256, material.keyType, nowMs())
                synchronized(lock) {
                    dir.mkdirs()
                    atomicWrite(File(dir, fileId(alias) + ENC), sealed)
                    writeMeta(File(dir, fileId(alias) + META), key)
                }
                AppResult.Success(key)
            } finally {
                plain.fill(0)
                material.privateKeyPem.fill('\u0000')
            }
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> io(block: () -> AppResult<T>): AppResult<T> =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecureStorageException) {
                AppResult.Failure(AppError.SecureStorageUnavailable(e.message))
            } catch (e: IOException) {
                AppResult.Failure(AppError.Storage(e.message ?: "I/O error"))
            } catch (e: Exception) {
                AppResult.Failure(AppError.Unexpected(e))
            }
        }

    private fun fileId(alias: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(alias.toByteArray(Charsets.UTF_8))

    private fun atomicWrite(
        target: File,
        bytes: ByteArray,
    ) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) throw IOException("rename failed")
    }

    private fun writeMeta(
        file: File,
        key: DeviceKey,
    ) {
        val p = Properties()
        p["alias"] = key.alias
        p["publicKeyOpenSsh"] = key.publicKeyOpenSsh
        p["fingerprint"] = key.fingerprint
        p["keyType"] = key.keyType
        p["createdAtMs"] = key.createdAtMs.toString()
        val out = java.io.ByteArrayOutputStream()
        p.store(out, null)
        atomicWrite(file, out.toByteArray())
    }

    private fun readMeta(file: File): DeviceKey? =
        try {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            DeviceKey(
                p.getProperty("alias"),
                p.getProperty("publicKeyOpenSsh"),
                p.getProperty("fingerprint"),
                p.getProperty("keyType"),
                p.getProperty("createdAtMs").toLong(),
            )
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val ENC = ".enc"
        const val META = ".meta"
    }
}
