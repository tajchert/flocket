package com.mtajchert.flocket.core.common.logging

/**
 * Minimal logging facade usable from pure-Kotlin modules. The app installs an implementation
 * backed by `android.util.Log` (debug builds) or a no-op (release); tests use [Logger.None] or a
 * recording fake.
 *
 * Never log secrets, private keys, passwords, terminal bytes or document contents.
 */
interface Logger {
    fun debug(
        tag: String,
        message: () -> String,
    )

    fun info(
        tag: String,
        message: () -> String,
    )

    fun warn(
        tag: String,
        message: () -> String,
        error: Throwable? = null,
    )

    fun error(
        tag: String,
        message: () -> String,
        error: Throwable? = null,
    )

    object None : Logger {
        override fun debug(
            tag: String,
            message: () -> String,
        ) = Unit

        override fun info(
            tag: String,
            message: () -> String,
        ) = Unit

        override fun warn(
            tag: String,
            message: () -> String,
            error: Throwable?,
        ) = Unit

        override fun error(
            tag: String,
            message: () -> String,
            error: Throwable?,
        ) = Unit
    }
}
