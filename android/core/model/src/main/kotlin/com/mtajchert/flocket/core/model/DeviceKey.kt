package com.mtajchert.flocket.core.model

/** A device SSH key managed by the app. The private half never leaves core:security. */
data class DeviceKey(
    val alias: String,
    /** `ssh-ed25519 AAAA… comment` — what the user adds to ~/.ssh/authorized_keys. */
    val publicKeyOpenSsh: String,
    /** `SHA256:<base64>` as printed by `ssh-keygen -lf`. */
    val fingerprint: String,
    /** e.g. `ssh-ed25519`, `ecdsa-sha2-nistp256`, `ssh-rsa`. */
    val keyType: String,
    val createdAtMs: Long,
)
