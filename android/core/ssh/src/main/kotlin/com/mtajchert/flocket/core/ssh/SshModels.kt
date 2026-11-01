package com.mtajchert.flocket.core.ssh

/** Where and as whom to connect. */
data class SshTarget(
    val hostname: String,
    val port: Int = 22,
    val username: String,
)

/** Credentials for one connection attempt. Arrays are not copied or wiped by this module. */
sealed interface SshCredentials {
    /** [openSshPrivateKeyPem] is an unencrypted or passphrase-protected private key in PEM form. */
    class PrivateKey(
        val openSshPrivateKeyPem: CharArray,
        val passphrase: CharArray? = null,
    ) : SshCredentials {
        override fun toString(): String = "PrivateKey(***)"
    }

    class Password(
        val password: CharArray,
    ) : SshCredentials {
        override fun toString(): String = "Password(***)"
    }
}

/** Trust decision for a presented host key. */
sealed interface HostKeyDecision {
    data object Trusted : HostKeyDecision

    /** Never seen before: abort, let the user confirm, pin, reconnect. */
    data object Unknown : HostKeyDecision

    /** A different key is pinned for this host. */
    data class Changed(
        val expectedFingerprint: String,
    ) : HostKeyDecision
}

/**
 * Port implemented by the pin store (`core:database`). Called during key exchange, i.e. before any
 * credential is sent. [fingerprintSha256] is `SHA256:<base64 without padding>` like
 * `ssh-keygen -lf`; [keyType] is the SSH key type, e.g. `ssh-ed25519`. Must not block for long.
 */
fun interface HostKeyVerifier {
    fun verify(
        hostname: String,
        port: Int,
        keyType: String,
        fingerprintSha256: String,
    ): HostKeyDecision
}

data class SshTimeouts(
    val connectMs: Int = 10_000,
    val keepAliveSeconds: Int = 15,
)

/** Outcome of a run-to-completion remote command. */
data class CommandResult(
    val stdout: String,
    val stderr: String,
    val exitStatus: Int?,
)

/** Runs a remote command to completion (exec, no PTY). Implemented by [SshSession]. */
fun interface CommandRunner {
    suspend fun run(command: List<String>): CommandResult
}
