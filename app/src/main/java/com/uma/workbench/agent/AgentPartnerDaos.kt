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
    @Query("SELECT * FROM agent_profiles WHERE id = :id LIMIT 1") suspend fun get(id: String): AgentProfileEntity?
    @Query("SELECT * FROM agent_profiles WHERE enabled = 1") suspend fun getAllEnabled(): List<AgentProfileEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(profile: AgentProfileEntity)
    @Query("UPDATE agent_profiles SET enabled = :enabled, updatedAt = :now WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean, now: Long)
    @Query("DELETE FROM agent_profiles WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface AgentDiaryDao {
    @Query("SELECT * FROM agent_diary_entries WHERE agentId = :agentId ORDER BY dateKey DESC, updatedAt DESC") fun observe(agentId: String): Flow<List<AgentDiaryEntryEntity>>
    @Query("SELECT * FROM agent_diary_entries WHERE agentId = :agentId AND dateKey = :dateKey LIMIT 1") suspend fun findForDate(agentId: String, dateKey: String): AgentDiaryEntryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: AgentDiaryEntryEntity)
    @Query("DELETE FROM agent_diary_entries WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface AgentGroupDao {
    @Query("SELECT * FROM agent_groups WHERE workspaceId = :workspaceId OR workspaceId IS NULL ORDER BY updatedAt DESC") fun observeForWorkspace(workspaceId: String?): Flow<List<AgentGroupEntity>>
    @Query("SELECT * FROM agent_groups WHERE id = :id LIMIT 1") suspend fun get(id: String): AgentGroupEntity?
    @Query("SELECT * FROM agent_groups WHERE id = :id LIMIT 1") fun observeById(id: String): Flow<AgentGroupEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(group: AgentGroupEntity)
    @Query("DELETE FROM agent_groups WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM agent_group_members WHERE groupId = :groupId ORDER BY joinedAt") suspend fun members(groupId: String): List<AgentGroupMemberEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMembers(members: List<AgentGroupMemberEntity>)
    @Query("DELETE FROM agent_group_members WHERE groupId = :groupId") suspend fun clearMembers(groupId: String)
    @Query("DELETE FROM agent_group_members WHERE groupId = :groupId AND agentId = :agentId") suspend fun removeMember(groupId: String, agentId: String)
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_group_messages WHERE groupId = :groupId") suspend fun nextMessageSequence(groupId: String): Long
    @Query("SELECT * FROM agent_group_messages WHERE groupId = :groupId ORDER BY sequence ASC") fun observeMessages(groupId: String): Flow<List<AgentGroupMessageEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMessage(message: AgentGroupMessageEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertContextSources(sources: List<AgentGroupContextSourceEntity>)
    @Query("SELECT * FROM agent_group_context_sources WHERE groupId = :groupId ORDER BY importedAt DESC") suspend fun contextSources(groupId: String): List<AgentGroupContextSourceEntity>
    @Query("UPDATE agent_group_messages SET status = :status, completedAt = :completedAt, errorMessage = :errorMessage, roundsCount = :roundsCount, usageJson = :usageJson WHERE id = :messageId")
    suspend fun updateMessageResult(messageId: String, status: String, completedAt: Long?, errorMessage: String?, roundsCount: Int, usageJson: String?)
    @Query("UPDATE agent_group_messages SET status = :status, requestId = :requestId, model = :model, startedAt = :startedAt WHERE id = :messageId")
    suspend fun updateMessageRunning(messageId: String, status: String, requestId: String?, model: String?, startedAt: Long?)
    @Query("UPDATE agent_group_messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)
    @Query("UPDATE agent_group_messages SET content = :content WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String)
    @Query("UPDATE agent_group_messages SET toolCallsJson = :toolCallsJson WHERE id = :messageId")
    suspend fun updateMessageToolCalls(messageId: String, toolCallsJson: String?)

    @Query("SELECT * FROM agent_group_messages WHERE groupId = :groupId ORDER BY sequence DESC LIMIT :limit")
    suspend fun getRecentMessages(groupId: String, limit: Int = 20): List<AgentGroupMessageEntity>

    @Query("SELECT g.* FROM agent_groups g INNER JOIN agent_group_members m ON g.id = m.groupId WHERE m.agentId = :agentId")
    suspend fun groupsContainingMember(agentId: String): List<AgentGroupEntity>
}

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE conversationId = :convId ORDER BY startedAt DESC")
    suspend fun listByConversation(convId: String): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int): List<AgentRunEntity>

    @Query("UPDATE agent_runs SET status = :status, completedAt = :completedAt, error = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, completedAt: Long?, error: String?)

    @Query("UPDATE agent_runs SET inputTokens = :input, outputTokens = :output, totalTokens = :total, roundsCount = :rounds, toolCallsCount = :toolCalls WHERE id = :id")
    suspend fun updateStats(
        id: String,
        input: Long,
        output: Long,
        total: Long,
        rounds: Int,
        toolCalls: Int
    )
}

@Dao
interface AgentToolCallRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentToolCallRecordEntity)

    @Query("SELECT * FROM agent_tool_call_records WHERE runId = :runId ORDER BY roundIndex ASC, timestamp ASC")
    suspend fun listByRun(runId: String): List<AgentToolCallRecordEntity>

    @Query("SELECT COUNT(*) FROM agent_tool_call_records WHERE runId = :runId AND status = :status")
    suspend fun countByStatus(runId: String, status: String): Int
}

@Dao
interface AgentToolApprovalRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentToolApprovalRecordEntity)

    @Query("SELECT * FROM agent_tool_approval_records WHERE runId = :runId ORDER BY timestamp ASC")
    suspend fun listByRun(runId: String): List<AgentToolApprovalRecordEntity>

    @Query("SELECT COUNT(*) FROM agent_tool_approval_records WHERE runId = :runId AND approved = 1")
    suspend fun countApproved(runId: String): Int
}
