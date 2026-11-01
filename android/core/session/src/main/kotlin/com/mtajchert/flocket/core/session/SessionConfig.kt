package com.mtajchert.flocket.core.session

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.protocol.CallTimeouts
import com.mtajchert.flocket.core.protocol.dto.DisplayDto
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Tunables of [CoreSessionImpl]. Defaults follow protocol/SPEC.md §8.4 and §11. */
data class SessionConfig(
    val backoffBase: Duration = 1.seconds,
    val backoffMultiplier: Double = 2.0,
    val backoffCap: Duration = 30.seconds,
    /** ±fraction applied to every backoff delay. */
    val backoffJitter: Double = 0.2,
    /** Source of jitter (inject a seeded/fixed one in tests). */
    val random: Random = Random.Default,
    /** No bytes at all for this long ⇒ the link is dead (heartbeats arrive every 15 s). */
    val watchdogTimeout: Duration = 45.seconds,
    val watchdogInterval: Duration = 5.seconds,
    /** Resyncs (gap/heartbeat ahead) tolerated per connection before it is dropped and rebuilt. */
    val maxResyncsPerConnection: Int = 5,
    val subscribeTimeout: Duration = 15.seconds,
    val readTimeout: Duration = CallTimeouts.READ,
    val runTimeout: Duration = CallTimeouts.RUN,
    val killTimeout: Duration = CallTimeouts.KILL,
    val sendTimeout: Duration = CallTimeouts.SEND,
    val interruptTimeout: Duration = CallTimeouts.KILL,
    val attachTimeout: Duration = 15.seconds,
    /** How long a read waits for the next connection before its single retry. */
    val readRetryWait: Duration = 15.seconds,
    /** How long `stop()` waits for CLOSE frames to be written before closing the link anyway. */
    val detachGrace: Duration = 2.seconds,
)

/** Jittered exponential backoff for the [failures]-th consecutive failure (1-based). */
internal fun SessionConfig.backoffDelayMs(failures: Int): Long {
    val base = backoffBase.inWholeMilliseconds.toDouble()
    val raw =
        min(
            backoffCap.inWholeMilliseconds.toDouble(),
            base * backoffMultiplier.pow((failures - 1).coerceAtLeast(0)),
        )
    val factor = 1.0 + backoffJitter * (2.0 * random.nextDouble() - 1.0)
    return (raw * factor).roundToLong().coerceAtLeast(0)
}

/** Errors only the user can resolve: stop retrying until [CoreSession.retryNow]. */
internal fun AppError.needsUser(): Boolean =
    when (this) {
        is AppError.HostKeyUnknown,
        is AppError.HostKeyChanged,
        is AppError.AuthFailed,
        is AppError.CoreNotFound,
        is AppError.CoreIncompatible,
        is AppError.InvalidInput,
        is AppError.SecureStorageUnavailable,
        -> true

        else -> false
    }

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

private fun String?.toWireColor(): String? = this?.takeIf { HEX_COLOR.matches(it) }?.lowercase()

/** SPEC §6.6: `#rrggbb` colors (invalid ones dropped) and `cell = [widthPx, heightPx]`. */
fun DisplayFacts.toDisplayDto(): DisplayDto =
    DisplayDto(
        fg = foreground.toWireColor(),
        bg = background.toWireColor(),
        cursor = cursor.toWireColor(),
        cell =
            if (cellWidthPx != null && cellHeightPx != null && cellWidthPx > 0 && cellHeightPx > 0) {
                listOf(cellWidthPx, cellHeightPx)
            } else {
                null
            },
    )
