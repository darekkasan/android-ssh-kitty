package com.kisshkitty.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name ASC")
    fun getAllHosts(): Flow<List<Host>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getHostById(id: String): Host?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHost(host: Host)

    @Update
    suspend fun updateHost(host: Host)

    @Delete
    suspend fun deleteHost(host: Host)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteHostById(id: String)
}
