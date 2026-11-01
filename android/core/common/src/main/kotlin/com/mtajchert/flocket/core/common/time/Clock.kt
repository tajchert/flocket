package com.mtajchert.flocket.core.common.time

/** Wall-clock source, injectable so time-dependent logic (staleness, backoff) is testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
