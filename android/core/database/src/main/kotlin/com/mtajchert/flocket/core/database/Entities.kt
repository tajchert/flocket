package com.mtajchert.flocket.core.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey val id: String,
    val label: String,
    val hostname: String,
    val port: Int,
    val username: String,
    /** [AUTH_KEY] or [AUTH_PASSWORD]. */
    val authType: String,
    val keyAlias: String?,
    val hcPath: String?,
    val session: String?,
    val sortOrder: Int,
    val createdAt: Long,
) {
    companion object {
        const val AUTH_KEY = "key"
        const val AUTH_PASSWORD = "password"
    }
}

@Entity(tableName = "pinned_host_keys", primaryKeys = ["hostname", "port"])
data class PinnedHostKeyEntity(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val pinnedAt: Long,
)
