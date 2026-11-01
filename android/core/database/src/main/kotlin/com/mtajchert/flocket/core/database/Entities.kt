package com.mtajchert.flocket.core.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
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

/**
 * Per-host terminal setting overrides (schema v2). One row per host that overrides anything; a null
 * column means "use the app default". A separate table (rather than columns on [HostEntity]) keeps
 * `HostDao.upsert` from ever touching settings, and the row cascades away with its host.
 */
@Entity(
    tableName = "host_settings",
    foreignKeys =
        [
            ForeignKey(
                HostEntity::class,
                parentColumns = ["id"],
                childColumns = ["hostId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class HostSettingsEntity(
    @PrimaryKey val hostId: String,
    val fontSizeSp: Float?,
    /** `TerminalColorScheme` name; unknown values are read as "no override". */
    val colorScheme: String?,
    val showExtraKeys: Boolean?,
    val keepScreenOn: Boolean?,
    /** `ClipboardWritePolicy` name. */
    val clipboardWritePolicy: String?,
    /** `BellMode` name. */
    val bell: String?,
    val autoConnect: Boolean?,
    val defaultWorkingDirectory: String?,
)
