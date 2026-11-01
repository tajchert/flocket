package com.mtajchert.flocket.core.ssh

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier

/**
 * Opens SSH connections. The host key is checked during key exchange, before authentication: any
 * decision other than [HostKeyDecision.Trusted] (or a verifier that throws) aborts the connection
 * before a credential is sent. Cancelling [connect] disconnects the half-open client promptly.
 */
class SshConnector {
    suspend fun connect(
        target: SshTarget,
        credentials: SshCredentials,
        verifier: HostKeyVerifier,
        timeouts: SshTimeouts = SshTimeouts(),
    ): AppResult<SshSession> {
        val client =
            withContext(Dispatchers.IO) {
                SshCrypto.ensureProvider()
                SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })
            }
        return coroutineScope {
            val attempt = async(Dispatchers.IO) { connectBlocking(client, target, credentials, verifier, timeouts) }
            try {
                attempt.await()
            } catch (e: CancellationException) {
                // Closing the socket unblocks the attempt; coroutineScope then waits for it to finish.
                client.closeQuietly()
                throw e
            }
        }
    }

    private fun connectBlocking(
        client: SSHClient,
        target: SshTarget,
        credentials: SshCredentials,
        verifier: HostKeyVerifier,
        timeouts: SshTimeouts,
    ): AppResult<SshSession> {
        // Written on SSHJ's reader thread, read here after connect() fails.
        val decision = AtomicReference<AppError?>(null)
        client.addHostKeyVerifier(
            object : SshjHostKeyVerifier {
                override fun verify(
                    hostname: String,
                    port: Int,
                    key: PublicKey,
                ): Boolean =
                    try {
                        val type = KeyType.fromKey(key).toString()
                        val fp = SshKeys.fingerprint(key)
                        when (val d = verifier.verify(hostname, port, type, fp)) {
                            HostKeyDecision.Trusted -> {
                                true
                            }

                            HostKeyDecision.Unknown -> {
                                decision.set(AppError.HostKeyUnknown(hostname, type, fp))
                                false
                            }

                            is HostKeyDecision.Changed -> {
                                decision.set(AppError.HostKeyChanged(hostname, type, d.expectedFingerprint, fp))
                                false
                            }
                        }
                    } catch (e: Exception) {
                        // Never accept a key we could not check.
                        decision.compareAndSet(null, AppError.Unexpected(e))
                        false
                    }

                override fun findExistingAlgorithms(
                    hostname: String,
                    port: Int,
                ): List<String> = emptyList()
            },
        )
        val method = if (credentials is SshCredentials.Password) "password" else "publickey"
        return try {
            client.connectTimeout = timeouts.connectMs
            // No socket read timeout (SO_TIMEOUT would drop idle sessions); SSHJ's transport timeout
            // bounds kex/auth/channel-open waits and keep-alive detects dead peers.
            client.transport.timeoutMs = timeouts.connectMs * 3
            client.connect(target.hostname, target.port)
            client.connection.keepAlive.keepAliveInterval = timeouts.keepAliveSeconds
            when (credentials) {
                is SshCredentials.Password -> {
                    // SSHJ wipes the array it is given; pass a copy so the caller's array stays intact.
                    client.authPassword(target.username, credentials.password.copyOf())
                }

                is SshCredentials.PrivateKey -> {
                    val finder = credentials.passphrase?.let { PasswordUtils.createOneOff(it.copyOf()) }
                    val keys =
                        try {
                            client.loadKeys(String(credentials.openSshPrivateKeyPem), null, finder)
                        } catch (e: Exception) {
                            client.closeQuietly()
                            return AppResult.Failure(
                                AppError.InvalidInput("privateKey", e.message?.take(200) ?: "unreadable key"),
                            )
                        }
                    client.authPublickey(target.username, keys)
                }
            }
            AppResult.Success(SshSession(client))
        } catch (e: Exception) {
            client.closeQuietly()
            AppResult.Failure(decision.get() ?: map(e, target, method))
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
            // SocketException covers ConnectException, NoRouteToHostException and resets.
            if (c is UnknownHostException || c is SocketException || c is SocketTimeoutException) {
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
