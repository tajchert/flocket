package com.mtajchert.flocket.journey

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64
import com.mtajchert.flocket.feature.terminal.impl.R as TerminalR
import com.mtajchert.flocket.feature.terminals.impl.R as TerminalsR

/*
 * Helpers for the link journeys (network loss, killed SSH link): cutting the link on the device or on the Mac,
 * and telling the app's SSH logins apart from the control connection's.
 */

/**
 * Runs one program on the device as the shell user (UiAutomation), without a shell: no `;`, pipes or quotes.
 * Unlike [HostControl.adb] it needs no network, so it can turn the network back on.
 */
fun deviceShell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor
        .AutoCloseInputStream(pfd)
        .bufferedReader()
        .use { it.readText() }
        .trim()
}

/**
 * Takes the emulator offline: Wi-Fi and mobile data off. Android removes the routes and destroys the sockets on
 * those networks, so the app's link and the [HostControl] control connection both drop at once.
 */
fun networkOff() {
    deviceShell("svc wifi disable")
    deviceShell("svc data disable")
}

/** Undoes [networkOff]. Asynchronous: the network is back a second or so later. */
fun networkOn() {
    deviceShell("svc wifi enable")
    deviceShell("svc data enable")
}

/** Sends text as key events to the focused view (the terminal), the same path as `adb shell input text`. */
fun typeKeys(text: String) = InstrumentationRegistry.getInstrumentation().sendStringSync(text)

/** The `SHA256:...` fingerprint of an OpenSSH public key line, as sshd logs it on `Accepted publickey`. */
fun sshFingerprint(publicKeyLine: String): String {
    val blob = Base64.getDecoder().decode(publicKeyLine.trim().split(' ')[1])
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** Fingerprint of the device key the seeded app logs in with (not the control key). */
fun JourneyRule.deviceKeyFingerprint(): String =
    runBlocking {
        sshFingerprint(
            app
                .deviceKeys()
                .keys
                .first()
                .single()
                .publicKeyOpenSsh,
        )
    }

/**
 * Starts a terminal from the app (New terminal, Default shell, Start), so the app is its owner, and waits for the
 * terminal screen. Returns its address. The hc session starts empty (JourneyRule), so it is the only terminal.
 */
fun JourneyRule.openOwnerTerminal(): String {
    compose.clickDescription(string(TerminalsR.string.new_terminal))
    compose.clickText(string(TerminalsR.string.new_terminal_start))
    compose.waitFor(hasContentDescription(string(TerminalR.string.terminal_action_more)), 15_000)
    return poll(10_000, "the new terminal in hc ls") { host.terminals().singleOrNull()?.address }
}

/** The terminal screen's "Reconnecting…" chip (shown while the bound stream's link is down). */
fun JourneyRule.reconnectingChip() = hasText(string(TerminalR.string.terminal_reconnecting))

/**
 * Product rule 3: [marker] (typed while the link was down) must never reach the core. Watches `hc read` for
 * [watchMs] so a late replay after the reconnect is caught too.
 */
fun HostControl.assertNeverReaches(
    address: String,
    marker: String,
    watchMs: Long,
) {
    val end = System.currentTimeMillis() + watchMs
    do {
        check(marker !in read(address)) { "keys typed while the link was down reached the core: $marker" }
        Thread.sleep(200)
    } while (System.currentTimeMillis() < end)
}

/** SSH logins the harness sshd accepted for [fingerprint] (one key), so other keys' logins don't count. */
fun HostControl.acceptedLogins(fingerprint: String): Int =
    sh(
        """awk -v fp=" ${'$'}2" '/Accepted publickey/ && index(${'$'}0, fp) { n++ } END { print n + 0 }' "${'$'}1/sshd.log"""",
        args.sshdDir,
        fingerprint,
    ).toInt()

/**
 * Pids of the harness sshd's `sshd-session` processes that serve a live login with [fingerprint] (the app's device
 * key). It cannot pick the wrong process:
 * - it only looks at direct children of the harness listener (pid from `$sshdDir/sshd.pid`), so nothing of the
 *   system sshd or any other user process is a candidate;
 * - a child counts only when its established TCP socket is `127.0.0.1:<harness port> -> 127.0.0.1:<client port>`
 *   and the **last** `Accepted publickey` line in the harness log for that client port carries [fingerprint]
 *   (so a client port reused by a later control login is not mistaken for an old app login);
 * - the session running this very script (the control connection) is an ancestor of the shell and is excluded
 *   explicitly, even though its login used the control key.
 */
fun HostControl.appLinkPids(fingerprint: String): List<Int> =
    sh(
        APP_LINK_PIDS_SCRIPT,
        args.sshdDir,
        args.port.toString(),
        fingerprint,
    ).lines().filter { it.isNotBlank() }.map { it.trim().toInt() }

/**
 * Cuts the app's SSH link on the Mac side: kills exactly the [appLinkPids] (at least one must exist). With
 * [holdDown], first stops the harness listener (open connections survive it, the control one included) so the
 * app cannot reconnect until [startListener]. Returns the killed pids.
 */
fun HostControl.killAppLink(
    fingerprint: String,
    holdDown: Boolean,
): List<Int> {
    val pids = appLinkPids(fingerprint)
    check(pids.isNotEmpty()) { "no sshd-session serves the app's login ($fingerprint)" }
    if (holdDown) stopListener()
    sh("kill \"$@\"", *pids.map { it.toString() }.toTypedArray())
    return pids
}

/** Pid of the `sshd-session` monitor (`[postauth]`) that serves this control connection: an ancestor of the shell. */
fun HostControl.controlSessionPid(): Int =
    sh(
        """
        a=${'$'}${'$'}
        while [ "${'$'}a" -gt 1 ]; do
            case "${'$'}(ps -o command= -p "${'$'}a")" in "sshd-session: "*"[postauth]"*) echo "${'$'}a"; exit 0 ;; esac
            a=${'$'}(ps -o ppid= -p "${'$'}a" | tr -d ' ')
        done
        exit 1
        """.trimIndent(),
    ).toInt()

/**
 * Kills the control connection's session [pid] (from [controlSessionPid]) after a network loss. The emulator drops
 * the socket without a FIN reaching the Mac, so that session would otherwise stay half-open until `down`. Only
 * kills it when it is still an `sshd-session ... [postauth]` with a socket on the harness port.
 */
fun HostControl.killStaleSession(pid: Int) {
    sh(
        """
        ps -o command= -p "${'$'}1" | grep -q '^sshd-session: .*\[postauth\]' || exit 0
        lsof -nP -a -p "${'$'}1" -iTCP -Fn 2>/dev/null | grep -q "^n127\.0\.0\.1:${'$'}2->" || exit 0
        kill "${'$'}1"
        """.trimIndent(),
        pid.toString(),
        args.port.toString(),
    )
}

/** Stops the harness sshd listener (`dev-sshd.sh stop`); established sessions keep running. */
fun HostControl.stopListener() {
    sh("FLOCKET_SSHD_DIR=\"$1\" \"$2\" stop >/dev/null", args.sshdDir, args.devSshd)
}

/** Starts the harness sshd listener again on the same port and host key; a no-op when it is running. */
fun HostControl.startListener() {
    sh("FLOCKET_SSHD_DIR=\"$1\" \"$2\" start \"$3\" >/dev/null", args.sshdDir, args.devSshd, args.port.toString())
}

// $1 sshd dir, $2 harness port, $3 fingerprint. Prints one pid per line. See appLinkPids for why it is safe.
private val APP_LINK_PIDS_SCRIPT =
    """
    set -e
    dir=${'$'}1 port=${'$'}2 fp=${'$'}3
    master=${'$'}(cat "${'$'}dir/sshd.pid")
    # Client ports whose last accepted login used the fingerprint.
    ports=${'$'}(awk -v fp="${'$'}fp" '/Accepted publickey/ {
        sub(/\r${'$'}/, "")  # sshd -E writes CRLF line ends
        for (i = 1; i < NF; i++) if (${'$'}i == "port") p = ${'$'}(i + 1)
        last[p] = ${'$'}NF
    } END { for (p in last) if (last[p] == fp) print p }' "${'$'}dir/sshd.log")
    # Ancestors of this shell: the control connection's own session is one of them.
    self=" "; a=${'$'}${'$'}
    while [ "${'$'}a" -gt 1 ]; do self="${'$'}self${'$'}a "; a=${'$'}(ps -o ppid= -p "${'$'}a" | tr -d ' '); done
    for pid in ${'$'}(pgrep -P "${'$'}master" || true); do
        case "${'$'}self" in *" ${'$'}pid "*) continue ;; esac
        peer=${'$'}(lsof -nP -a -p "${'$'}pid" -iTCP -sTCP:ESTABLISHED -Fn 2>/dev/null |
            sed -n "s/^n127\.0\.0\.1:${'$'}port->127\.0\.0\.1:\([0-9]*\)${'$'}/\1/p" | head -1)
        [ -n "${'$'}peer" ] || continue
        for p in ${'$'}ports; do [ "${'$'}p" = "${'$'}peer" ] && echo "${'$'}pid"; done
    done
    true
    """.trimIndent()
