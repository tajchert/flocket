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
import java.io.DataOutputStream
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
    /** Unencrypted PEM (OpenSSH for ed25519, PKCS#8 otherwise). The caller must zero it. */
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
        return ed25519Material(seed, pub, comment)
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
                val pubRaw = ed25519Raw(blob)
                AppResult.Success(ed25519Material(seed, pubRaw, comment))
            } else {
                val text = unencryptedPem(priv, pub)
                AppResult.Success(
                    KeyMaterial(type, publicLine(type, blob, comment), fingerprintOfBlob(blob), text.toCharArray()),
                )
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
    ): String {
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
        return "-----BEGIN $label-----\n${b64Mime(der)}\n-----END $label-----\n"
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
        val blob = sshString(ED25519.toByteArray()) + sshString(pub)
        val check = SecureRandom().nextInt()
        val priv =
            ByteArrayOutputStream()
                .also { bos ->
                    val o = DataOutputStream(bos)
                    o.writeInt(check)
                    o.writeInt(check)
                    o.write(sshString(ED25519.toByteArray()))
                    o.write(sshString(pub))
                    o.write(sshString(seed + pub))
                    o.write(sshString(comment.toByteArray()))
                    var pad = 1
                    while (bos.size() % 8 != 0) o.writeByte(pad++)
                }.toByteArray()
        val body =
            ByteArrayOutputStream()
                .also { bos ->
                    bos.write("openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1))
                    bos.write(sshString("none".toByteArray()))
                    bos.write(sshString("none".toByteArray()))
                    bos.write(sshString(ByteArray(0)))
                    DataOutputStream(bos).writeInt(1)
                    bos.write(sshString(blob))
                    bos.write(sshString(priv))
                }.toByteArray()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n${b64Mime(body)}\n-----END OPENSSH PRIVATE KEY-----\n"
        return KeyMaterial(ED25519, publicLine(ED25519, blob, comment), fingerprintOfBlob(blob), pem.toCharArray())
    }

    private fun publicLine(
        type: String,
        blob: ByteArray,
        comment: String,
    ) = "$type ${b64.encodeToString(blob)}" + if (comment.isNotEmpty()) " $comment" else ""

    private fun sshString(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).also {
            it.writeInt(bytes.size)
            it.write(bytes)
        }
        return bos.toByteArray()
    }

    private fun b64Mime(bytes: ByteArray): String = Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(bytes)
}
