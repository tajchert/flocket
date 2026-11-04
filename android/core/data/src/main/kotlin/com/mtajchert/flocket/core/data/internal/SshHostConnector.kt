package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.common.logging.Logger
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.data.HostRepository
import com.mtajchert.flocket.core.data.HostTrustRepository
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.protocol.transport.ByteTransport
import com.mtajchert.flocket.core.protocol.transport.DirectFileReader
import com.mtajchert.flocket.core.protocol.transport.DirectFileSource
import com.mtajchert.flocket.core.protocol.transport.SiblingTransportSource
import com.mtajchert.flocket.core.protocol.transport.TransportException
import com.mtajchert.flocket.core.security.KeyVault
import com.mtajchert.flocket.core.ssh.CommandRunner
import com.mtajchert.flocket.core.ssh.HcLocator
import com.mtajchert.flocket.core.ssh.HostKeyDecision
import com.mtajchert.flocket.core.ssh.HostKeyVerifier
import com.mtajchert.flocket.core.ssh.SshConnector
import com.mtajchert.flocket.core.ssh.SshCredentials
import com.mtajchert.flocket.core.ssh.SshSession
import com.mtajchert.flocket.core.ssh.SshTarget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** Opens the transport to one host's `hc connect` and holds in-memory passwords. */
internal interface HostConnector {
    suspend fun open(hostId: HostId): AppResult<ByteTransport>

    /** Keeps a copy of [password] for this process only. */
    fun setPassword(
        hostId: HostId,
        password: CharArray,
    )

    /** Drops (and zeroes) the remembered password. */
    fun clearPassword(hostId: HostId)
}

/** Seam over the final [SshSession] so the glue is testable without a network. */
internal interface SshLink : CommandRunner {
    suspend fun exec(command: List<String>): ByteTransport

    /** An SFTP reader on this link. Throws `DirectFileException(UNAVAILABLE)` when the host refuses SFTP. */
    suspend fun openFileReader(): DirectFileReader

    suspend fun close()
}

internal fun interface SshOpener {
    suspend fun open(
        target: SshTarget,
        credentials: SshCredentials,
        verifier: HostKeyVerifier,
    ): AppResult<SshLink>
}

internal class ConnectorSshOpener(
    private val connector: SshConnector = SshConnector(),
) : SshOpener {
    override suspend fun open(
        target: SshTarget,
        credentials: SshCredentials,
        verifier: HostKeyVerifier,
    ): AppResult<SshLink> =
        when (val r = connector.connect(target, credentials, verifier)) {
            is AppResult.Success -> AppResult.Success(SessionLink(r.value))
            is AppResult.Failure -> r
        }

    private class SessionLink(
        private val session: SshSession,
    ) : SshLink {
        override suspend fun exec(command: List<String>): ByteTransport = session.exec(command)

        override suspend fun openFileReader(): DirectFileReader = session.openFileReader()

        override suspend fun run(command: List<String>) = session.run(command)

        override suspend fun close() = session.close()
    }
}

internal class SshHostConnector(
    private val hosts: HostRepository,
    private val hcPathCache: HcPathCache,
    private val trust: HostTrustRepository,
    private val vault: KeyVault,
    private val opener: SshOpener,
    private val logger: Logger = Logger.None,
) : HostConnector {
    private val passwords = ConcurrentHashMap<HostId, CharArray>()

    override fun setPassword(
        hostId: HostId,
        password: CharArray,
    ) {
        passwords.put(hostId, password.copyOf())?.fill('\u0000')
    }

    override fun clearPassword(hostId: HostId) {
        passwords.remove(hostId)?.fill('\u0000')
    }

    override suspend fun open(hostId: HostId): AppResult<ByteTransport> =
        try {
            openInternal(hostId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Failure(AppError.Unexpected(e))
        }

    private suspend fun openInternal(hostId: HostId): AppResult<ByteTransport> {
        val host = hosts.host(hostId) ?: return AppResult.Failure(AppError.InvalidInput("host", "no longer exists"))

        // Preload the pin: the verifier runs synchronously during key exchange and must not suspend.
        val pin = trust.pinned(host.hostname, host.port)
        val verifier =
            HostKeyVerifier { _, _, _, fingerprint ->
                when {
                    pin == null -> HostKeyDecision.Unknown
                    pin.fingerprint == fingerprint -> HostKeyDecision.Trusted
                    else -> HostKeyDecision.Changed(pin.fingerprint)
                }
            }

        val credentials: SshCredentials =
            when (val auth = host.auth) {
                is HostAuth.Key -> {
                    when (val pem = vault.loadPrivateKeyPem(auth.keyAlias)) {
                        is AppResult.Success -> SshCredentials.PrivateKey(pem.value)
                        is AppResult.Failure -> return pem
                    }
                }

                HostAuth.Password -> {
                    val pw =
                        passwords[hostId]
                            ?: return AppResult.Failure(AppError.AuthFailed(host.username, listOf("password")))
                    SshCredentials.Password(pw.copyOf())
                }
            }

        val link =
            try {
                opener.open(SshTarget(host.hostname, host.port, host.username), credentials, verifier)
            } finally {
                when (credentials) {
                    is SshCredentials.PrivateKey -> credentials.openSshPrivateKeyPem.fill('\u0000')
                    is SshCredentials.Password -> credentials.password.fill('\u0000')
                }
            }
        val ssh =
            when (link) {
                is AppResult.Success -> {
                    link.value
                }

                is AppResult.Failure -> {
                    // A rejected password is forgotten so the UI asks again instead of looping.
                    if (link.error is AppError.AuthFailed && host.auth is HostAuth.Password) clearPassword(hostId)
                    return link
                }
            }

        try {
            val path =
                when (val located = HcLocator.locate(ssh, host.hcPath)) {
                    is AppResult.Success -> {
                        located.value
                    }

                    is AppResult.Failure -> {
                        closeQuietly(ssh)
                        return located
                    }
                }
            if (path != host.hcPath) {
                runCatching { hcPathCache.cacheHcPath(hostId, path) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            val command = HcLocator.coreCommand(path, host.session)
            val transport = ssh.exec(command)
            return AppResult.Success(ClosingTransport(transport, ssh, command))
        } catch (e: CancellationException) {
            closeQuietly(ssh)
            throw e
        } catch (e: TransportException) {
            closeQuietly(ssh)
            logger.warn(TAG, { "could not start hc connect" })
            return AppResult.Failure(AppError.Unreachable(host.hostname, e.message))
        } catch (e: Exception) {
            closeQuietly(ssh)
            return AppResult.Failure(AppError.Unexpected(e))
        }
    }

    private suspend fun closeQuietly(link: SshLink) {
        withContext(NonCancellable) {
            try {
                link.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Closes the SSH connection together with its exec channel. Also a [SiblingTransportSource]: a
     * sibling is another exec of the same [command] on the same SSH connection (the session's
     * transfer connection). Closing a sibling closes only its channel; closing this transport closes
     * every sibling, every reader and the link. Also a [DirectFileSource]: readers (SFTP) run on the
     * same link.
     */
    private class ClosingTransport(
        private val inner: ByteTransport,
        private val link: SshLink,
        private val command: List<String>,
    ) : ByteTransport,
        SiblingTransportSource,
        DirectFileSource {
        private val siblings = CopyOnWriteArrayList<ByteTransport>()
        private val readers = CopyOnWriteArrayList<DirectFileReader>()

        @Volatile
        private var closed = false

        override suspend fun read(buffer: ByteArray): Int = inner.read(buffer)

        override suspend fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = inner.write(bytes, offset, length)

        override fun diagnostics(): String = inner.diagnostics()

        override suspend fun openSibling(): ByteTransport {
            if (closed) throw TransportException("connection closed")
            val raw = link.exec(command)
            val sibling =
                object : ByteTransport by raw {
                    override suspend fun close() {
                        siblings -= this
                        raw.close()
                    }
                }
            siblings += sibling
            if (closed) {
                // Lost the race against close(): never leave a channel open on a closing link.
                runCatching { sibling.close() }
                throw TransportException("connection closed")
            }
            return sibling
        }

        override suspend fun openFileReader(): DirectFileReader {
            if (closed) throw TransportException("connection closed")
            val raw = link.openFileReader()
            val reader =
                object : DirectFileReader by raw {
                    override suspend fun close() {
                        readers -= this
                        raw.close()
                    }
                }
            readers += reader
            if (closed) {
                runCatching { reader.close() }
                throw TransportException("connection closed")
            }
            return reader
        }

        override suspend fun close() {
            closed = true
            try {
                inner.close()
            } finally {
                readers.forEach { runCatching { it.close() } }
                siblings.forEach { runCatching { it.close() } }
                link.close()
            }
        }
    }

    private companion object {
        const val TAG = "SshHostConnector"
    }
}
