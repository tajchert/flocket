package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64

/** Result of generating or importing a key: all public facts plus the unencrypted PEM. */
class KeyMaterial(
    val keyType: String,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    /** Unencrypted PEM (OpenSSH for ed25519, SEC1 for ECDSA, PKCS#8 for RSA). The caller must zero it. */
    val privateKeyPem: CharArray,
)

object SshKeys {
    private const val ED25519 = "ssh-ed25519"
    private val b64 = Base64.getEncoder()

    /** Generates an Ed25519 key. [comment] ends up in both the public line and the PEM. */
    fun generateEd25519(comment: String): KeyMaterial {
        SshCrypto.ensureProvider()
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = gen.generateKeyPair()
        val seed = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        try {
            return ed25519Material(seed, pub, comment)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Parses [pem] (OpenSSH, PKCS#8 or PKCS#1; ed25519, ecdsa, rsa) with optional [passphrase], and
     * returns it re-encoded without encryption. Failures are `InvalidInput`.
     */
    fun import(
        pem: CharArray,
        passphrase: CharArray?,
        comment: String = "",
    ): AppResult<KeyMaterial> =
        try {
            val (priv, pub) = parsePair(pem, passphrase)
            val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
            val type = KeyType.fromKey(pub).toString()
            if (type == ED25519) {
                val encoded = priv.encoded
                val seed = encoded.copyOfRange(encoded.size - 32, encoded.size)
                try {
                    AppResult.Success(ed25519Material(seed, ed25519Raw(blob), comment))
                } finally {
                    encoded.fill(0)
                    seed.fill(0)
                }
            } else {
                val pem = unencryptedPem(priv, pub)
                AppResult.Success(KeyMaterial(type, publicLine(type, blob, comment), fingerprintOfBlob(blob), pem))
            }
        } catch (e: Exception) {
            AppResult.Failure(AppError.InvalidInput("privateKey", e.message?.take(200) ?: "unreadable key"))
        }

    /** `SHA256:<base64, no padding>` of the SSH wire-format public key blob (`ssh-keygen -lf`). */
    fun fingerprintOfBlob(blob: ByteArray): String =
        "SHA256:" +
            Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    fun fingerprint(key: PublicKey): String = fingerprintOfBlob(Buffer.PlainBuffer().putPublicKey(key).compactData)

    fun keyType(key: PublicKey): String = KeyType.fromKey(key).toString()

    private fun parsePair(
        pem: CharArray,
        passphrase: CharArray?,
    ): Pair<PrivateKey, PublicKey> {
        SshCrypto.ensureProvider()
        SSHClient().use { client ->
            val finder = passphrase?.let { PasswordUtils.createOneOff(it.copyOf()) }
            val provider = client.loadKeys(String(pem), null, finder)
            return provider.private to provider.public
        }
    }

    /** RSA: PKCS#8. EC: SEC1 with embedded public key (SSHJ's reader requires it; the JDK omits it). */
    private fun unencryptedPem(
        priv: PrivateKey,
        pub: PublicKey,
    ): CharArray {
        val (label, der) =
            if (priv is java.security.interfaces.ECPrivateKey) {
                val spki =
                    org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
                        .getInstance(pub.encoded)
                val ec =
                    org.bouncycastle.asn1.sec.ECPrivateKey(
                        priv.params.order.bitLength(),
                        priv.s,
                        spki.publicKeyData,
                        curveOid(keyType(pub)),
                    )
                "EC PRIVATE KEY" to ec.encoded
            } else {
                "PRIVATE KEY" to priv.encoded
            }
        try {
            return pemChars(label, der)
        } finally {
            der.fill(0)
        }
    }

    // Named-curve OID from the SSH key type; provider-encoded params may be explicit instead.
    private fun curveOid(sshType: String) =
        org.bouncycastle.asn1.ASN1ObjectIdentifier(
            when (sshType) {
                "ecdsa-sha2-nistp256" -> "1.2.840.10045.3.1.7"
                "ecdsa-sha2-nistp384" -> "1.3.132.0.34"
                "ecdsa-sha2-nistp521" -> "1.3.132.0.35"
                else -> error("unsupported EC curve: $sshType")
            },
        )

    private fun ed25519Raw(blob: ByteArray): ByteArray {
        // blob = string "ssh-ed25519", string pub(32)
        val b = Buffer.PlainBuffer(blob)
        b.readString()
        return b.readBytes()
    }

    private fun ed25519Material(
        seed: ByteArray,
        pub: ByteArray,
        comment: String,
    ): KeyMaterial {
        val blob = WipingBuffer().apply { sshString(ED25519.toByteArray()).sshString(pub) }.toByteArray()
        val check = SecureRandom().nextInt()
        val keyPair = seed + pub
        val priv = WipingBuffer()
        val body = WipingBuffer()
        try {
            priv.int32(check).int32(check)
            priv
                .sshString(ED25519.toByteArray())
                .sshString(pub)
                .sshString(keyPair)
                .sshString(comment.toByteArray())
            var pad = 1
            while (priv.size() % 8 != 0) priv.write(pad++)
            body.write("openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1))
            body.sshString("none".toByteArray()).sshString("none".toByteArray()).sshString(ByteArray(0))
            body.int32(1).sshString(blob)
            priv.writeSshStringTo(body)
            val pem = body.wipingBytes { pemChars("OPENSSH PRIVATE KEY", it) }
            return KeyMaterial(ED25519, publicLine(ED25519, blob, comment), fingerprintOfBlob(blob), pem)
        } finally {
            keyPair.fill(0)
            priv.wipe()
            body.wipe()
        }
    }

    /** PEM armour built straight into a [CharArray], so no String copy of the secret exists. */
    private fun pemChars(
        label: String,
        der: ByteArray,
    ): CharArray {
        val b64 = Base64.getMimeEncoder(70, byteArrayOf('\n'.code.toByte())).encode(der)
        val head = "-----BEGIN $label-----\n"
        val tail = "\n-----END $label-----\n"
        val out = CharArray(head.length + b64.size + tail.length)
        head.toCharArray(out, 0)
        for (i in b64.indices) out[head.length + i] = b64[i].toInt().toChar()
        tail.toCharArray(out, head.length + b64.size)
        b64.fill(0)
        return out
    }

    /** ByteArrayOutputStream whose internal buffer can be zeroed. */
    private class WipingBuffer : ByteArrayOutputStream() {
        fun int32(v: Int) =
            apply {
                write(v ushr 24)
                write(v ushr 16)
                write(v ushr 8)
                write(v)
            }

        fun sshString(bytes: ByteArray) =
            apply {
                int32(bytes.size)
                write(bytes, 0, bytes.size)
            }

        /** Writes this buffer's content as an SSH string into [target] without an extra copy. */
        fun writeSshStringTo(target: WipingBuffer) {
            target.int32(count)
            target.write(buf, 0, count)
        }

        fun <T> wipingBytes(use: (ByteArray) -> T): T {
            val bytes = toByteArray()
            try {
                return use(bytes)
            } finally {
                bytes.fill(0)
            }
        }

        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun publicLine(
        type: String,
        blob: ByteArray,
        comment: String,
    ) = "$type ${b64.encodeToString(blob)}" + if (comment.isNotEmpty()) " $comment" else ""
}
