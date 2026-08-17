package com.uma.workbench.plugin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginRegistryDao {
    @Query("SELECT * FROM plugin_registry ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PluginRegistryEntity>>

    @Query("SELECT * FROM plugin_registry WHERE pluginId = :pluginId LIMIT 1")
    suspend fun get(pluginId: String): PluginRegistryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginRegistryEntity)

    @Query("DELETE FROM plugin_registry WHERE pluginId = :pluginId")
    suspend fun delete(pluginId: String)

    @Query("UPDATE plugin_registry SET state = :state, updatedAt = :updatedAt, lastError = :lastError WHERE pluginId = :pluginId")
    suspend fun updateState(pluginId: String, state: String, updatedAt: Long, lastError: String?)
}
