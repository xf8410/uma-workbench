package com.uma.workbench.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentProfileDao {
    @Query("SELECT * FROM agent_profiles WHERE workspaceId = :workspaceId OR workspaceId IS NULL ORDER BY updatedAt DESC")
    fun observeForWorkspace(workspaceId: String?): Flow<List<AgentProfileEntity>>

    @Query("SELECT * FROM agent_profiles WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AgentProfileEntity)

    @Query("UPDATE agent_profiles SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("DELETE FROM agent_profiles WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AgentDiaryDao {
    @Query("SELECT * FROM agent_diary_entries WHERE agentId = :agentId ORDER BY dateKey DESC, updatedAt DESC")
    fun observe(agentId: String): Flow<List<AgentDiaryEntryEntity>>

    @Query("SELECT * FROM agent_diary_entries WHERE agentId = :agentId AND dateKey = :dateKey LIMIT 1")
    suspend fun findForDate(agentId: String, dateKey: String): AgentDiaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AgentDiaryEntryEntity)

    @Query("DELETE FROM agent_diary_entries WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AgentGroupDao {
    @Query("SELECT * FROM agent_groups WHERE workspaceId = :workspaceId OR workspaceId IS NULL ORDER BY updatedAt DESC")
    fun observeForWorkspace(workspaceId: String?): Flow<List<AgentGroupEntity>>

    @Query("SELECT * FROM agent_groups WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: AgentGroupEntity)

    @Query("DELETE FROM agent_groups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM agent_group_members WHERE groupId = :groupId ORDER BY joinedAt")
    suspend fun members(groupId: String): List<AgentGroupMemberEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<AgentGroupMemberEntity>)

    @Query("DELETE FROM agent_group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_group_messages WHERE groupId = :groupId")
    suspend fun nextMessageSequence(groupId: String): Long

    @Query("SELECT * FROM agent_group_messages WHERE groupId = :groupId ORDER BY sequence ASC")
    fun observeMessages(groupId: String): Flow<List<AgentGroupMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: AgentGroupMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContextSources(sources: List<AgentGroupContextSourceEntity>)

    @Query("SELECT * FROM agent_group_context_sources WHERE groupId = :groupId ORDER BY importedAt DESC")
    suspend fun contextSources(groupId: String): List<AgentGroupContextSourceEntity>
}
