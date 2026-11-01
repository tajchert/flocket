package com.mtajchert.flocket.core.data.internal

import com.mtajchert.flocket.core.common.error.AppError
import com.mtajchert.flocket.core.model.HostAuth

internal val SESSION_NAME = Regex("^[a-z0-9][a-z0-9._-]{0,31}$")

/** Terminal ids look like `t7`; a session named that way would be ambiguous in the CLI. */
internal val ID_SHAPED = Regex("^t[0-9]+$")

internal fun invalid(
    field: String,
    reason: String,
) = AppError.InvalidInput(field, reason)

/** Returns the first validation error of a host draft, or null. */
internal fun validateHost(
    label: String,
    hostname: String,
    port: Int,
    username: String,
    auth: HostAuth,
    hcPath: String?,
    session: String?,
): AppError.InvalidInput? =
    when {
        label.isBlank() -> invalid("label", "must not be blank")
        hostname.isBlank() -> invalid("hostname", "must not be blank")
        hostname.any { it.isWhitespace() } -> invalid("hostname", "must not contain spaces")
        port !in 1..65535 -> invalid("port", "must be between 1 and 65535")
        username.isBlank() -> invalid("username", "must not be blank")
        auth is HostAuth.Key && auth.keyAlias.isBlank() -> invalid("auth", "a key must be selected")
        hcPath != null && !hcPath.startsWith("/") -> invalid("hcPath", "must be an absolute path")
        session != null && !SESSION_NAME.matches(session) ->
            invalid("session", "use lowercase letters, digits, '.', '_' or '-' (max 32)")
        session != null && ID_SHAPED.matches(session) -> invalid("session", "must not look like a terminal id")
        else -> null
    }
