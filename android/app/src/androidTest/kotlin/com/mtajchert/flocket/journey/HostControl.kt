package com.mtajchert.flocket.journey

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.mtajchert.flocket.core.common.result.AppResult
import com.mtajchert.flocket.core.ssh.HostKeyDecision
import com.mtajchert.flocket.core.ssh.SshConnector
import com.mtajchert.flocket.core.ssh.SshCredentials
import com.mtajchert.flocket.core.ssh.SshSession
import com.mtajchert.flocket.core.ssh.SshTarget
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * The Mac side of a journey, reached from the emulator over the harness sshd with a control key the runner
 * authorized. It verifies the same host key the app is asked to trust ([Args.hostFingerprint]) and runs `hc`
 * only in the isolated session ([Args.hcSession]). Get one through [HostRule].
 */
class HostControl private constructor(
    val args: Args,
    /** The host key's type as the app sees it (`ssh-ed25519`), for seeding a pinned key. */
    val hostKeyType: String,
    private val session: SshSession,
) : AutoCloseable {
    /** The instrumentation arguments (DECISIONS.md rule 2). Not a data class: [toString] must not print the key. */
    class Args(
        val host: String,
        val port: Int,
        val user: String,
        val hostFingerprint: String,
        val hcPath: String,
        val hcSession: String,
        val sshdDir: String,
        val devSshd: String,
        val serial: String,
        val adbPath: String,
        internal val controlKeyPem: String,
    ) {
        override fun toString() = "Args($user@$host:$port, hcSession=$hcSession, serial=$serial)"
    }

    /** One entry of `hc ls --json`. */
    data class HcTerminal(
        val address: String,
        val name: String?,
        val state: String?,
    )

    /** `hc <args>` in the isolated session; returns trimmed stdout. Throws on a non-zero exit. */
    fun hc(vararg hcArgs: String): String =
        run(listOf("env", "HC_SESSION=${args.hcSession}", "TMPDIR=/tmp", args.hcPath) + hcArgs)

    /** Live terminals (`hc ls --json`), in core order; with [all], finished ones too (`--all`). */
    fun terminals(all: Boolean = false): List<HcTerminal> {
        val list = JSONObject(if (all) hc("ls", "--json", "--all") else hc("ls", "--json")).getJSONArray("terminals")
        return (0 until list.length()).map {
            val t = list.getJSONObject(it)
            HcTerminal(
                t.getString("address"),
                t.optString("name").ifEmpty { null },
                t.optString("state").ifEmpty { null },
            )
        }
    }

    /** The terminal's screen and scrollback as text (`hc read`). */
    fun read(address: String): String = hc("read", address)

    /** `hc get --json <address>`: the full record (`cols`, `rows`, `state`, `pid`, `exit_code`, `status`, `name`, ...). */
    fun get(address: String): JSONObject = JSONObject(hc("get", "--json", address))

    /** Starts `sh` in the background (`hc run --bg --no-parent`) and returns its address. */
    fun runShell(name: String): String =
        JSONObject(
            hc("run", "--bg", "--no-parent", "--json", "--name", name, "--cwd", "/tmp", "sh"),
        ).getString("address")

    /**
     * Kills every live terminal in the isolated session and removes the finished ones (`hc rm`), so the app's
     * list starts empty. A terminal that is already gone is ignored.
     */
    fun killAll() {
        for (t in terminals()) runCatching { hc("kill", t.address) }
        for (t in terminals(all = true)) runCatching { hc("rm", t.address) }
    }

    /** Authorizes an OpenSSH public key line on the harness sshd (`dev-sshd.sh authorize`). */
    fun authorize(publicKeyLine: String) {
        run(listOf("env", "FLOCKET_SSHD_DIR=${args.sshdDir}", args.devSshd, "authorize", publicKeyLine))
    }

    /** `adb -s <serial> <args>` run on the Mac, for what the device can't do to itself (network toggles, ...). */
    fun adb(vararg adbArgs: String): String = run(listOf(args.adbPath, "-s", args.serial) + adbArgs)

    private fun run(command: List<String>): String =
        runBlocking {
            val r = session.run(command)
            // Never echo env values or key material: the command is described by its program and arguments only.
            check(r.exitStatus == 0) {
                "${command.dropWhile { it == "env" || '=' in it }.joinToString(
                    " ",
                )} exited ${r.exitStatus}: ${r.stderr.trim()}"
            }
            r.stdout.trim()
        }

    override fun close() = runBlocking { session.close() }

    companion object {
        /** Null when the runner passed no arguments (a plain connected run): the journey is skipped. */
        fun argsOrNull(bundle: Bundle): Args? {
            val key = bundle.getString("controlKey") ?: return null

            fun required(name: String) =
                requireNotNull(bundle.getString(name)) { "instrumentation argument $name is missing" }
            return Args(
                host = bundle.getString("host", "10.0.2.2"),
                port = required("port").toInt(),
                user = required("user"),
                hostFingerprint = required("hostFingerprint"),
                hcPath = required("hcPath"),
                hcSession = required("hcSession"),
                sshdDir = required("sshdDir"),
                devSshd = required("devSshd"),
                serial = required("serial"),
                adbPath = required("adbPath"),
                controlKeyPem = String(Base64.getDecoder().decode(key)),
            )
        }

        fun connect(args: Args): HostControl =
            runBlocking {
                val keyType = AtomicReference<String>()
                val result =
                    SshConnector().connect(
                        SshTarget(args.host, args.port, args.user),
                        SshCredentials.PrivateKey(args.controlKeyPem.toCharArray()),
                        verifier = { _, _, type, fp ->
                            keyType.set(type)
                            if (fp ==
                                args.hostFingerprint
                            ) {
                                HostKeyDecision.Trusted
                            } else {
                                HostKeyDecision.Changed(args.hostFingerprint)
                            }
                        },
                    )
                when (result) {
                    is AppResult.Success -> {
                        HostControl(args, keyType.get(), result.value)
                    }

                    is AppResult.Failure -> {
                        error(
                            "control connection to ${args.host}:${args.port} failed: ${result.error}",
                        )
                    }
                }
            }
    }
}

/**
 * Class rule that opens one [HostControl] for the test class and closes it afterwards. Without the runner's
 * arguments it skips the class (an assumption failure, so a plain `connectedDebugAndroidTest` passes).
 *
 * ```
 * companion object { @JvmField @ClassRule val host = HostRule() }
 * ```
 */
class HostRule : TestRule {
    private var current: HostControl? = null

    val control: HostControl get() = checkNotNull(current) { "HostRule must be a @ClassRule" }

    override fun apply(
        base: Statement,
        description: Description,
    ) = object : Statement() {
        override fun evaluate() {
            val args = HostControl.argsOrNull(InstrumentationRegistry.getArguments())
            assumeTrue("device journeys need the harness: scripts/e2e-device.sh journey", args != null)
            HostControl.connect(args!!).use {
                current = it
                try {
                    base.evaluate()
                } finally {
                    current = null
                }
            }
        }
    }
}
