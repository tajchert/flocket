package com.mtajchert.flocket.core.database

import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * Hosts, pinned SSH host keys and per-host settings overrides (v2, auto-migrated from v1). Schema is exported to `schemas/`; there is deliberately NO
 * destructive migration: bump [version] and add a real `Migration`.
 */
@Database(
    entities = [HostEntity::class, PinnedHostKeyEntity::class, HostSettingsEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class FlocketDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao

    abstract fun pinnedHostKeyDao(): PinnedHostKeyDao

    abstract fun hostSettingsDao(): HostSettingsDao

    companion object {
        const val NAME = "flocket.db"
    }
}
