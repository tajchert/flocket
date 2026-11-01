package com.mtajchert.flocket.journey

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mtajchert.flocket.core.model.ConnectionState
import org.json.JSONObject
import com.mtajchert.flocket.feature.terminal.impl.R as TerminalR

/*
 * Helpers for the lifecycle journeys (rotation, background, lock). They drive the device the way a user does
 * (UiDevice: rotate, HOME, power) and check the outcome on the core (`hc get`, `hc read`) and on the sshd log
 * (reconnects), never on pixels.
 */

val uiDevice: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

/** A terminal's size on the core (`hc get`). */
data class Grid(
    val cols: Int,
    val rows: Int,
) {
    override fun toString() = "${cols}x$rows"
}

/** herdr-core's size for a terminal with no viewer (SPEC §7.5). */
val HEADLESS = Grid(200, 120)

fun HostControl.size(address: String): Grid = get(address).let { Grid(it.getInt("cols"), it.getInt("rows")) }

/** Polls until the core reports [address] at a size that satisfies [ok]; returns it. */
fun HostControl.awaitSize(
    address: String,
    what: String,
    timeoutMs: Long = 10_000,
    ok: (Grid) -> Boolean,
): Grid {
    var last: Grid? = null
    return runCatching { poll(timeoutMs, what) { size(address).also { last = it }.takeIf(ok) } }
        .getOrElse { throw AssertionError("$address: $what; last size $last", it) }
}

/**
 * Waits until the core reports [address] at a size that satisfies [ok] and has not changed for [stableMs]. The
 * phone's grid settles a few hundred milliseconds after the screen opens (bars and chips lay out), so a size
 * read at the first change can be a row or two off.
 */
fun HostControl.awaitStableSize(
    address: String,
    what: String,
    stableMs: Long = 1_000,
    timeoutMs: Long = 10_000,
    ok: (Grid) -> Boolean,
): Grid {
    var candidate = awaitSize(address, what, timeoutMs, ok)
    var since = System.currentTimeMillis()
    val end = since + timeoutMs
    while (System.currentTimeMillis() - since < stableMs) {
        check(System.currentTimeMillis() < end) { "$address: $what; size never settled, last $candidate" }
        Thread.sleep(200)
        val now = size(address)
        if (now != candidate) {
            candidate = now
            since = System.currentTimeMillis()
        }
    }
    check(ok(candidate)) { "$address: $what; settled at $candidate" }
    return candidate
}

/** Opens the terminal named [name] from the list and returns the phone grid it settles at (owner, ADR-0007). */
fun JourneyRule.openAsOwner(
    name: String,
    address: String,
): Grid {
    openFromList(name)
    return host.awaitStableSize(address, "the phone to take the size on open") { it != HEADLESS }
}

/**
 * Asserts the core keeps [address] at [expected] for [forMs]: a size change the app should not make would
 * otherwise land a few hundred milliseconds after the step that caused it.
 */
fun HostControl.assertSizeStays(
    address: String,
    expected: Grid,
    forMs: Long = 1_500,
    why: String,
) {
    val end = System.currentTimeMillis() + forMs
    while (System.currentTimeMillis() < end) {
        val now = size(address)
        if (now != expected) throw AssertionError("$address: $why; expected $expected, core says $now")
        Thread.sleep(200)
    }
}

/**
 * A second viewer on the Mac, like Herdr desktop: an hc terminal whose pty is [size] runs `hc attach <address>`,
 * which makes it the most recently active viewer (it takes the size, SPEC §7.5). [takeSize] presses a key in it
 * (the desktop user typing) to take the size back. Killed with the other terminals by `HostControl.killAll`.
 * The command uses the absolute `hc` path: the core's terminals inherit its PATH, which is minimal
 * (`/usr/bin:/bin:...`) when the core was started over the harness sshd.
 */
class MacViewer private constructor(
    private val host: HostControl,
    val address: String,
    val size: Grid,
    val viewerAddress: String,
) {
    /** The desktop user presses Enter in its terminal: DATA makes it the size owner again. */
    fun takeSize() {
        host.hc("keys", viewerAddress, "enter")
    }

    companion object {
        fun attach(
            host: HostControl,
            address: String,
            size: Grid = Grid(100, 40),
        ): MacViewer {
            val viewer =
                org.json
                    .JSONObject(
                        host.hc(
                            "run",
                            "--bg",
                            "--no-parent",
                            "--json",
                            "--name",
                            "deskview",
                            "--cwd",
                            "/tmp",
                            "sh",
                            "-c",
                            "stty cols ${size.cols} rows ${size.rows}; exec ${host.args.hcPath} attach $address",
                        ),
                    ).getString("address")
            host.awaitSize(address, "the Mac viewer to take the size") { it == size }
            return MacViewer(host, address, size, viewer)
        }
    }
}

/** Opens the terminal named [name] from the list and waits for its screen. */
fun JourneyRule.openFromList(name: String) {
    compose.click(hasText(name, substring = true) and hasClickAction(), 15_000)
    awaitTerminalScreen()
}

fun JourneyRule.awaitTerminalScreen(timeoutMs: Long = 15_000) =
    compose.waitFor(hasContentDescription(string(TerminalR.string.terminal_action_more)), timeoutMs)

/**
 * Types `echo <marker>` + Enter into the focused terminal (key events, like `adb shell input text`) and waits
 * until the core shows the command's output. The command is typed as `echo ab""cd`, so only the output line
 * matches [marker], not the echoed command line.
 */
fun JourneyRule.typeAndExpect(
    address: String,
    marker: String,
    timeoutMs: Long = 10_000,
) {
    require(marker.length > 2)
    InstrumentationRegistry.getInstrumentation().sendStringSync("echo ${marker.take(2)}\"\"${marker.drop(2)}\n")
    poll(timeoutMs, "$marker in hc read $address") { host.read(address).takeIf { marker in it } }
}

/** The seeded host's connection state, as the app's `ConnectionManager` reports it. */
fun JourneyRule.connection(): ConnectionState? =
    app
        .connections()
        .overview.value
        .firstOrNull { hostId == null || it.host.id == hostId }
        ?.session
        ?.connection

/** Waits until the app has dropped the host's session (Idle: detached, ADR-0006). */
fun JourneyRule.awaitDetached(
    why: String,
    timeoutMs: Long = 5_000,
) {
    runCatching { poll(timeoutMs, why) { Unit.takeIf { connection() is ConnectionState.Idle } } }
        .getOrElse { throw AssertionError("$why; connection is ${connection()}", it) }
}

/** Asserts the host stays Online for [forMs] (no detach, no reconnect in progress). */
fun JourneyRule.assertStaysOnline(
    forMs: Long,
    why: String,
) {
    val end = System.currentTimeMillis() + forMs
    while (System.currentTimeMillis() < end) {
        val c = connection()
        if (c !is ConnectionState.Online) throw AssertionError("$why; connection is $c")
        Thread.sleep(200)
    }
}

/** Counts reconnects across [block]: new `Accepted publickey` lines in the sshd log (DECISIONS.md rule 12). */
inline fun JourneyRule.reconnectsDuring(block: () -> Unit): Int {
    val before = host.acceptedLogins()
    block()
    return host.acceptedLogins() - before
}

/** HOME, as the user presses it. The app's process lifecycle reports ON_STOP ~700 ms later. */
fun pressHome() {
    uiDevice.pressHome()
}

/**
 * Brings the app's existing task back to the front, which is what tapping it in Recents does: the same activity
 * and ViewModels come back (no new launch), and the test checks that the scenario's activity is the one resumed.
 * A launcher-icon start is not used: `ActivityScenario` started the activity with its own intent, so a launcher
 * intent need not match the task's root and could start a second activity instead of returning to this one.
 */
fun JourneyRule.returnToApp() {
    val tasks = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val task = checkNotNull(tasks.appTasks.firstOrNull()) { "the app has no task to return to" }
    task.moveToFront()
    poll(5_000, "the same activity resumed") { Unit.takeIf { scenario.state == Lifecycle.State.RESUMED } }
}

/** Whether the ADR-0006 keep-alive runs as a foreground service right now (`dumpsys activity services`). */
fun keepAliveForeground(): Boolean {
    val dump = uiDevice.executeShellCommand("dumpsys activity services $APP_PACKAGE/$KEEP_ALIVE_SERVICE")
    return KEEP_ALIVE_SERVICE in dump && "isForeground=true" in dump
}

private val APP_PACKAGE get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName
private const val KEEP_ALIVE_SERVICE = "com.mtajchert.flocket.keepalive.TerminalKeepAliveService"

/** Screen on and the (insecure) keyguard dismissed, the way a swipe unlock leaves it. */
fun unlockScreen() {
    uiDevice.wakeUp()
    uiDevice.executeShellCommand("wm dismiss-keyguard")
    uiDevice.waitForIdle()
}
