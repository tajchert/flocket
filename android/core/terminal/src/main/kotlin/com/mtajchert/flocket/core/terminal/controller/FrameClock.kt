package com.mtajchert.flocket.core.terminal.controller

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Source of display frame ticks (vsync). The controller publishes at most one snapshot per tick.
 * Production: [ChoreographerFrameClock]; tests: a manual clock.
 */
fun interface FrameClock {
    /** Suspends until the next frame and returns its time in nanoseconds. */
    suspend fun awaitFrame(): Long
}

/**
 * [FrameClock] backed by the main thread's [Choreographer]. May be awaited from any thread; it
 * reuses one callback object, so a steady stream of frames allocates nothing but the coroutine
 * continuation. One instance per awaiting coroutine (it holds a single pending continuation).
 */
class ChoreographerFrameClock :
    FrameClock,
    Choreographer.FrameCallback,
    Runnable {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var pending: CancellableContinuation<Long>? = null

    override suspend fun awaitFrame(): Long =
        suspendCancellableCoroutine { cont ->
            pending = cont
            if (Looper.myLooper() == main.looper) run() else main.post(this)
            cont.invokeOnCancellation { pending = null }
        }

    /** Runs on the main thread: register for the next vsync. */
    override fun run() {
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val cont = pending ?: return
        pending = null
        cont.resume(frameTimeNanos)
    }
}
