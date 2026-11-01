package com.mtajchert.flocket.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: String): HostEntity?

    @Upsert
    suspend fun upsert(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM hosts")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE hosts SET hcPath = :hcPath WHERE id = :id")
    suspend fun updateHcPath(
        id: String,
        hcPath: String,
    )
}

@Dao
interface PinnedHostKeyDao {
    @Query("SELECT * FROM pinned_host_keys WHERE hostname = :hostname AND port = :port")
    suspend fun get(
        hostname: String,
        port: Int,
    ): PinnedHostKeyEntity?

    @Query("SELECT * FROM pinned_host_keys ORDER BY hostname, port")
    fun observeAll(): Flow<List<PinnedHostKeyEntity>>

    @Upsert
    suspend fun upsert(key: PinnedHostKeyEntity)

    @Query("DELETE FROM pinned_host_keys WHERE hostname = :hostname AND port = :port")
    suspend fun delete(
        hostname: String,
        port: Int,
    )
}
