package com.mtajchert.flocket.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

/**
 * Hosts and pinned SSH host keys. Schema is exported to `schemas/`; there is deliberately NO
 * destructive migration: bump [version] and add a real `Migration`.
 */
@Database(
    entities = [HostEntity::class, PinnedHostKeyEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FlocketDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao

    abstract fun pinnedHostKeyDao(): PinnedHostKeyDao

    companion object {
        const val NAME = "flocket.db"
    }
}
