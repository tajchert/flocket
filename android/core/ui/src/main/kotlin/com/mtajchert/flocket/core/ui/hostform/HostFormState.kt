@file:Suppress("ktlint:standard:max-line-length")

package com.mtajchert.flocket.core.ui.hostform

import androidx.annotation.StringRes
import com.mtajchert.flocket.core.data.HostDraft
import com.mtajchert.flocket.core.model.Host
import com.mtajchert.flocket.core.model.HostAuth
import com.mtajchert.flocket.core.model.HostId
import com.mtajchert.flocket.core.ui.R
import java.io.Serializable

enum class AuthKind { Key, Password }

enum class HostFormField { Label, Hostname, Username, Port, Key, HcPath, Session }

enum class HostFormError(
    @StringRes val message: Int,
) {
    Required(R.string.form_error_required),
    InvalidHostname(R.string.form_error_hostname),
    InvalidPort(R.string.form_error_port),
    InvalidSession(R.string.form_error_session),
    InvalidHcPath(R.string.form_error_hc_path),
    NoKey(R.string.form_error_key),
}

/** Editable, saveable (SavedStateHandle) state of the add/edit host form. Pure data, no Android types. */
data class HostFormState(
    val id: String? = null,
    val label: String = "",
    val hostname: String = "",
    val username: String = "",
    val port: String = "22",
    val authKind: AuthKind = AuthKind.Key,
    val keyAlias: String? = null,
    val hcPath: String = "",
    val session: String = "",
    val advancedExpanded: Boolean = false,
) : Serializable {
    /** Validation errors by field; empty when the form can be saved. Core name rules: 1-32 of a-z0-9-_. starting alnum. */
    fun validate(): Map<HostFormField, HostFormError> =
        buildMap {
            if (label.isBlank()) put(HostFormField.Label, HostFormError.Required)
            if (hostname.isBlank()) {
                put(HostFormField.Hostname, HostFormError.Required)
            } else if (hostname.any { it.isWhitespace() }) {
                put(HostFormField.Hostname, HostFormError.InvalidHostname)
            }
            if (username.isBlank()) put(HostFormField.Username, HostFormError.Required)
            if (port.toIntOrNull()?.let { it in 1..65535 } != true) put(HostFormField.Port, HostFormError.InvalidPort)
            if (authKind == AuthKind.Key && keyAlias == null) put(HostFormField.Key, HostFormError.NoKey)
            if (hcPath.isNotBlank() &&
                !hcPath.trim().startsWith("/")
            ) {
                put(HostFormField.HcPath, HostFormError.InvalidHcPath)
            }
            if (session.isNotBlank() &&
                !SESSION_NAME.matches(session.trim())
            ) {
                put(HostFormField.Session, HostFormError.InvalidSession)
            }
        }

    /** Call only when [validate] is empty. */
    fun toDraft(): HostDraft =
        HostDraft(
            id = id?.let(::HostId),
            label = label.trim(),
            hostname = hostname.trim(),
            port = port.trim().toInt(),
            username = username.trim(),
            auth = if (authKind == AuthKind.Password) HostAuth.Password else HostAuth.Key(requireNotNull(keyAlias)),
            hcPath = hcPath.trim().ifEmpty { null },
            session = session.trim().ifEmpty { null },
        )

    companion object {
        private val SESSION_NAME = Regex("^[a-z0-9][a-z0-9_.-]{0,31}$")

        fun from(host: Host): HostFormState =
            HostFormState(
                id = host.id.value,
                label = host.label,
                hostname = host.hostname,
                username = host.username,
                port = host.port.toString(),
                authKind = if (host.auth is HostAuth.Password) AuthKind.Password else AuthKind.Key,
                keyAlias = (host.auth as? HostAuth.Key)?.keyAlias,
                hcPath = host.hcPath.orEmpty(),
                session = host.session.orEmpty(),
                advancedExpanded = host.hcPath != null || host.session != null || host.port != 22,
            )
    }
}
