package com.mtajchert.flocket.journey

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.util.regex.Pattern
import com.mtajchert.flocket.feature.terminal.impl.R as TerminalR

/**
 * Large agent shares against a real `hc` (T6): a 120 MiB file downloads with progress, cancel leaves no `.part`, the
 * terminal keeps echoing while it runs, and a shared APK installs through the system confirmation. The host side
 * runs `flocket-share` (the repo's tool, by absolute path: terminals started over SSH have a minimal PATH).
 */
@RunWith(AndroidJUnit4::class)
class DownloadJourneyTest {
    private val journey = JourneyRule(host)

    /** SFTP is off for tests annotated [NoSftp] (set before the app connects, restored after). */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(SftpSwitch(host)).around(journey)

    private val ui get() = journey.compose
    private val dir = "/tmp/flocket-dl-journey"
    private val sharesDir get() = File(journey.targetContext.cacheDir, "shares")

    /** `scripts/` of the repo, derived from the harness's `dev-sshd.sh` path. */
    private val scriptsDir get() = File(journey.host.args.devSshd).parentFile!!
    private val shareTool get() = File(scriptsDir.parentFile!!.parentFile, "protocol/tools/flocket-share")

    @Before
    fun hostDir() {
        journey.host.sh("rm -rf \"$1\" && mkdir -p \"$1\"", dir)
    }

    @After
    fun cleanHost() {
        runCatching { journey.host.sh("rm -rf \"$1\"", dir) }
    }

    @Test
    fun largeShareDownloadsWithProgressAndMatchesChecksum() {
        val hostSha = makeBigFile()
        openShare("big.bin")
        ui.waitFor(hasTestTag("share_cancel"), 30_000)
        val done = awaitDownloaded("big.bin", 240_000)
        check(done.length() == BIG_BYTES) { "size ${done.length()} != $BIG_BYTES" }
        val sha = sha256(done)
        check(sha == hostSha) { "sha256 $sha != host $hostSha" }
    }

    @Test
    fun speedOf200MiBOverSftp() {
        val started = sftpSessions()
        val ms = timedDownload(MAX_BYTES)
        check(sftpSessions() > started) { "the harness sshd started no SFTP session: the download was not SFTP" }
        report("sftp", MAX_BYTES, ms)
    }

    @NoSftp
    @Test
    fun speedOf200MiBChunkedWhenSftpIsUnavailable() {
        val started = sftpSessions()
        val ms = timedDownload(MAX_BYTES)
        check(sftpSessions() == started) { "an SFTP session started: this was not the chunked fallback" }
        report("chunked", MAX_BYTES, ms)
    }

    @Test
    fun cancelMidwayRemovesPartFile() {
        makeBigFile()
        openShare("big.bin")
        ui.waitFor(hasTestTag("share_cancel"), 30_000)
        poll(120_000, "10% of the file in the .part") {
            Unit.takeIf { parts().sumOf { it.length() } >= BIG_BYTES / 10 }
        }
        ui.click(hasTestTag("share_cancel"))
        poll(15_000, "no .part left") { Unit.takeIf { parts().isEmpty() } }
        Thread.sleep(1_500)
        check(parts().isEmpty()) { "a .part came back: ${parts()}" }
        check(sharesDir.walkTopDown().none { it.name == "big.bin" }) { "a finished file exists after cancel" }
    }

    @Test
    fun terminalKeepsEchoingDuringDownload() {
        makeBigFile()
        val address = openShare("big.bin")
        ui.waitFor(hasTestTag("share_cancel"), 30_000)
        poll(60_000, "download running") { Unit.takeIf { parts().sumOf { it.length() } > 1_000_000 } }
        // Closing the viewer keeps the download running (DECISIONS.md); the terminal is then typeable.
        ui.clickDescription(journey.string(TerminalR.string.share_viewer_close))
        ui.waitFor(hasContentDescription(journey.string(TerminalR.string.terminal_action_more)), 10_000)
        val before = parts().sumOf { it.length() }
        val started = System.currentTimeMillis()
        InstrumentationRegistry.getInstrumentation().sendStringSync("echo marker-$MARK\n")
        poll(2_000, "marker in hc read within 2 s") { journey.host.read(address).takeIf { "marker-$MARK\n" in it } }
        val ms = System.currentTimeMillis() - started
        val running = parts().isNotEmpty() || sharesDir.walkTopDown().any { it.name == "big.bin" }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString(
                    "stream",
                    "echo-during-download: ${ms}ms, part bytes before=$before, after=${parts().sumOf {
                        it.length()
                    }}\n",
                )
            },
        )
        check(running) { "the download had already finished; the echo proves nothing" }
    }

    @Test
    fun apkShareAsksToInstallAndInstalls() {
        val pkg = "com.mtajchert.flocket.e2e.tiny"
        val label = "Flocket E2E Tiny"
        // The runner grants REQUEST_INSTALL_PACKAGES to the app before every test (a grant from here kills the process).
        journey.host.sh("\"$1/make-test-apk.sh\" \"$2/tiny.apk\" \"$3\" \"$4\"", scriptsDir.path, dir, pkg, label)
        runCatching { journey.host.adb("shell", "pm", "uninstall", pkg) }
        try {
            openShare("tiny.apk")
            val installAction = journey.string(TerminalR.string.apk_install_action)
            val dialogTitle = journey.string(TerminalR.string.apk_install_title, label)
            ui.waitFor(hasText(dialogTitle) or hasTestTag("share_install"), 60_000)
            if (ui.onAllNodes(hasText(dialogTitle)).fetchSemanticsNodes().isEmpty()) {
                ui.click(hasTestTag("share_install"))
                ui.waitFor(hasText(dialogTitle), 10_000)
            }
            ui.click(hasText(installAction) and hasClickAction() and !hasTestTag("share_install"))

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val confirm =
                device.wait(
                    Until.findObject(
                        By.pkg(Pattern.compile(".*packageinstaller.*")).text(Pattern.compile("Install|Update")),
                    ),
                    30_000,
                )
            checkNotNull(confirm) { "the system install confirmation did not appear" }
            confirm.click()
            poll(60_000, "$pkg in pm list packages") {
                Unit.takeIf { pkg in journey.host.adb("shell", "pm", "list", "packages", pkg) }
            }
        } finally {
            runCatching { journey.host.adb("shell", "pm", "uninstall", pkg) }
        }
    }

    /** Shares a [bytes] random file, downloads it, checks its SHA-256; returns the milliseconds from Open to Done. */
    private fun timedDownload(bytes: Long): Long {
        val hostSha = makeBigFile(bytes)
        openShare("big.bin")
        val started = System.currentTimeMillis()
        val done = awaitDownloaded("big.bin", 600_000)
        val ms = System.currentTimeMillis() - started
        check(done.length() == bytes) { "size ${done.length()} != $bytes" }
        check(sha256(done) == hostSha) { "sha256 differs from the host's" }
        return ms
    }

    private fun report(
        method: String,
        bytes: Long,
        ms: Long,
    ) {
        val line = "download-speed $method ${bytes / MIB} MiB: $ms ms, ${"%.1f".format(bytes / 1e6 / (ms / 1e3))} MB/s"
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString(
                    "stream",
                    line + "\n",
                )
            },
        )
    }

    /** SFTP sessions the harness sshd started (logged at LogLevel VERBOSE; a refused request logs a different line). */
    private fun sftpSessions(): Int =
        journey.host
            .sh("grep -c \"Starting session: subsystem 'sftp'\" \"$1/sshd.log\" || true", journey.host.args.sshdDir)
            .toInt()

    /** A random file in [dir] named big.bin; returns its SHA-256. */
    private fun makeBigFile(bytes: Long = BIG_BYTES): String {
        journey.host.sh("head -c $bytes /dev/urandom > \"$1/big.bin\"", dir)
        return journey.host.sh("shasum -a 256 \"$1/big.bin\" | cut -d' ' -f1", dir)
    }

    /** Opens a shell terminal in the app, shares [name] from [dir] with flocket-share, opens the card. Returns the address. */
    private fun openShare(name: String): String {
        val shell = "dlsh"
        val address = journey.host.runShell(shell)
        ui.click(hasText(shell, substring = true) and hasClickAction(), 15_000)
        ui.waitFor(hasContentDescription(journey.string(TerminalR.string.terminal_action_more)), 15_000)
        poll(15_000, "shell prompt on the core") { journey.host.read(address).takeIf { it.isNotBlank() } }
        val hcDir = File(journey.host.args.hcPath).parent
        journey.host.hc(
            "send",
            address,
            "PATH=$hcDir:\$PATH $shareTool $dir/$name",
        )
        ui.waitFor(hasText(journey.string(TerminalR.string.share_card_open)), 20_000)
        ui.clickText(journey.string(TerminalR.string.share_card_open))
        return address
    }

    private fun parts() = sharesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()

    private fun awaitDownloaded(
        name: String,
        timeoutMs: Long,
    ): File =
        poll(timeoutMs, "$name downloaded") {
            sharesDir.walkTopDown().firstOrNull { it.isFile && it.name == name }
        }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        @JvmField
        @ClassRule
        val host = HostRule()

        private const val MIB = 1024L * 1024
        private const val BIG_BYTES = 120L * MIB
        private const val MAX_BYTES = 200L * MIB
        private const val MARK = "dl42"
    }
}

/** Marks a download journey that runs against a sshd without the SFTP subsystem (the chunked fallback). */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class NoSftp

/** Comments out the harness sshd's `Subsystem sftp` line for [NoSftp] tests and restores it after; new connections only. */
private class SftpSwitch(
    private val hostRule: HostRule,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                if (description.getAnnotation(NoSftp::class.java) == null) return base.evaluate()
                set(on = false)
                try {
                    base.evaluate()
                } finally {
                    runCatching { set(on = true) }
                }
            }
        }

    private fun set(on: Boolean) {
        val dir = hostRule.control.args.sshdDir
        val edit = if (on) "s/^#Subsystem sftp/Subsystem sftp/" else "s/^Subsystem sftp/#Subsystem sftp/"
        // sshd re-reads its config on SIGHUP; sessions that are already open keep the old one.
        hostRule.control.sh(
            "sed -i '' \"$1\" \"$2/sshd_config\" && kill -HUP \"$(cat \"$2/sshd.pid\")\" && sleep 1",
            edit,
            dir,
        )
    }
}
