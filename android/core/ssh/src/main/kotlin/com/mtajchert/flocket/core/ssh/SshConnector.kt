package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import kotlin.coroutines.cancellation.CancellationException
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier

/** Opens SSH connections. The host key is checked during key exchange, before authentication. */
class SshConnector {
    suspend fun connect(
        target: SshTarget,
        credentials: SshCredentials,
        verifier: HostKeyVerifier,
        timeouts: SshTimeouts = SshTimeouts(),
    ): AppResult<SshSession> =
        withContext(Dispatchers.IO) {
            SshCrypto.ensureProvider()
            val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
            val client = SSHClient(config)
            var decision: AppError? = null
            var rejected = false
            client.addHostKeyVerifier(
                object : SshjHostKeyVerifier {
                    override fun verify(
                        hostname: String,
                        port: Int,
                        key: PublicKey,
                    ): Boolean {
                        val type = KeyType.fromKey(key).toString()
                        val fp = SshKeys.fingerprint(key)
                        return when (val d = verifier.verify(hostname, port, type, fp)) {
                            HostKeyDecision.Trusted -> {
                                true
                            }

                            HostKeyDecision.Unknown -> {
                                decision = AppError.HostKeyUnknown(hostname, type, fp)
                                rejected = true
                                false
                            }

                            is HostKeyDecision.Changed -> {
                                decision = AppError.HostKeyChanged(hostname, type, d.expectedFingerprint, fp)
                                rejected = true
                                false
                            }
                        }
                    }

                    override fun findExistingAlgorithms(
                        hostname: String,
                        port: Int,
                    ): List<String> = emptyList()
                },
            )
            val method: String
            try {
                client.connectTimeout = timeouts.connectMs
                client.timeout = timeouts.connectMs * 3
                client.connect(target.hostname, target.port)
                client.connection.keepAlive.keepAliveInterval = timeouts.keepAliveSeconds
                when (credentials) {
                    is SshCredentials.Password -> {
                        method = "password"
                        client.authPassword(target.username, credentials.password)
                    }

                    is SshCredentials.PrivateKey -> {
                        method = "publickey"
                        val finder = credentials.passphrase?.let { PasswordUtils.createOneOff(it.copyOf()) }
                        val keys =
                            try {
                                client.loadKeys(String(credentials.openSshPrivateKeyPem), null, finder)
                            } catch (e: Exception) {
                                client.closeQuietly()
                                return@withContext AppResult.Failure(
                                    AppError.InvalidInput("privateKey", e.message?.take(200) ?: "unreadable key"),
                                )
                            }
                        client.authPublickey(target.username, keys)
                    }
                }
                AppResult.Success(SshSession(client))
            } catch (e: CancellationException) {
                client.closeQuietly()
                throw e
            } catch (e: Exception) {
                client.closeQuietly()
                AppResult.Failure(
                    decision
                        ?: map(e, target, if (credentials is SshCredentials.Password) "password" else "publickey"),
                )
            }
        }

    private fun map(
        e: Exception,
        target: SshTarget,
        method: String,
    ): AppError {
        if (e is UserAuthException) return AppError.AuthFailed(target.username, listOf(method))
        var c: Throwable? = e
        while (c != null) {
            if (c is UnknownHostException || c is ConnectException || c is NoRouteToHostException ||
                c is SocketTimeoutException
            ) {
                return AppError.Unreachable(target.hostname, c.message?.take(200) ?: c.javaClass.simpleName)
            }
            c = c.cause
        }
        return AppError.Unexpected(e)
    }
}

internal fun SSHClient.closeQuietly() {
    try {
        disconnect()
    } catch (_: Exception) {
    }
}
