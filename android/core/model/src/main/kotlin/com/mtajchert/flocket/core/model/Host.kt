package com.mtajchert.flocket.core.model

/**
 * A machine the user configured to reach over SSH. Secrets are never part of this model; they
 * live in `core:security` and are referenced by [auth].
 */
data class Host(
    val id: HostId,
    /** User-facing name, e.g. "Studio Mac". */
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val auth: HostAuth,
    /** Absolute path of `hc` on the host; null = discover on first connect (then stored). */
    val hcPath: String? = null,
    /** `hc` session to reach; null = the host's default session. */
    val session: String? = null,
) {
    val endpoint: CoreEndpoint get() = CoreEndpoint(id, session)
}

sealed interface HostAuth {
    /** Device key managed by the app (generated or imported), referenced by alias. */
    data class Key(
        val keyAlias: String,
    ) : HostAuth

    /** Password typed by the user on each connect (not stored by default). */
    data object Password : HostAuth
}

/** A pinned SSH host key (trust on first use, confirmed by the user). */
data class PinnedHostKey(
    val hostname: String,
    val port: Int,
    val keyType: String,
    /** `SHA256:<base64>` as shown by `ssh-keygen -lf`. */
    val fingerprint: String,
    val pinnedAtMs: Long,
)
