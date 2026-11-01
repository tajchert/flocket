package com.mtajchert.flocket.core.ssh

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicInteger

/** Embedded MINA sshd with scripted exec commands: `echo`, `banner`, `missing`, `chunks`. */
class TestSshServer(
    private val password: String = "pw",
    private val authorizedFingerprints: Set<String> = emptySet(),
) : AutoCloseable {
    val authAttempts = AtomicInteger()
    private val hostKeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val hostFingerprint: String = SshKeys.fingerprint(hostKeyPair.public)
    val hostKeyType: String = SshKeys.keyType(hostKeyPair.public)
    private val sshd: SshServer =
        SshServer.setUpDefaultServer().apply {
            port = 0
            host = "127.0.0.1"
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
            passwordAuthenticator =
                org.apache.sshd.server.auth.password.PasswordAuthenticator { _, p, _ ->
                    authAttempts.incrementAndGet()
                    p == password
                }
            publickeyAuthenticator =
                org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key: PublicKey, _ ->
                    authAttempts.incrementAndGet()
                    org.apache.sshd.common.config.keys.KeyUtils
                        .getFingerPrint(key) in authorizedFingerprints
                }
            commandFactory = CommandFactory { _: ChannelSession, cmd: String -> ScriptedCommand(cmd) }
        }

    init {
        check(SecurityUtils.isBouncyCastleRegistered() || true)
        sshd.start()
    }

    val port: Int get() = sshd.port

    override fun close() = sshd.stop(true)

    private class ScriptedCommand(
        private val cmd: String,
    ) : Command {
        private lateinit var input: InputStream
        private lateinit var out: OutputStream
        private lateinit var err: OutputStream
        private lateinit var exit: ExitCallback

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            this.out = out
        }

        override fun setErrorStream(err: OutputStream) {
            this.err = err
        }

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(
            channel: ChannelSession,
            env: Environment,
        ) {
            Thread {
                var status = 0
                try {
                    when {
                        cmd.contains("echo") -> {
                            err.write(ByteArray(20_000) { 'e'.code.toByte() })
                            err.write("TAIL-MARK".toByteArray())
                            err.flush()
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                out.flush()
                            }
                        }

                        cmd.contains("banner") -> {
                            out.write("WELCOME\n".toByteArray())
                            out.flush()
                            val buf = ByteArray(1024)
                            val n = input.read(buf)
                            if (n > 0) out.write(buf, 0, n)
                            out.flush()
                        }

                        cmd.contains("chunks") -> {
                            repeat(10) {
                                out.write(ByteArray(7) { b -> (it * 7 + b).toByte() })
                                out.flush()
                                Thread.sleep(5)
                            }
                        }

                        else -> {
                            err.write("hc: not found\n".toByteArray())
                            err.flush()
                            status = 127
                        }
                    }
                } catch (_: Exception) {
                    status = 1
                } finally {
                    runCatching { out.flush() }
                    exit.onExit(status)
                }
            }.start()
        }

        override fun destroy(channel: ChannelSession) = Unit
    }
}
