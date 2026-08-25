package com.uma.workbench.agent

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "agent_profiles", indices = [Index("workspaceId"), Index("enabled")])
data class AgentProfileEntity(
    @androidx.room.PrimaryKey val id: String,
    val workspaceId: String?,
    val name: String,
    val avatarUri: String?,
    val identityMarkdown: String,
    val soulMarkdown: String,
    val userMarkdown: String?,
    val systemPrompt: String?,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "agent_diary_entries", indices = [Index("agentId"), Index("dateKey")])
data class AgentDiaryEntryEntity(
    @androidx.room.PrimaryKey val id: String,
    val agentId: String,
    val dateKey: String,
    val title: String,
    val content: String,
    val sourceConversationId: String?,
    val sourceMessageRange: String?,
    val status: String = "DRAFT",
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "agent_groups", indices = [Index("workspaceId"), Index("managerAgentId")])
data class AgentGroupEntity(
    @androidx.room.PrimaryKey val id: String,
    val workspaceId: String?,
    val name: String,
    val description: String?,
    val managerAgentId: String,
    val turnPolicy: String = "MANAGER_SELECTS",
    val groupPrompt: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "agent_group_members", primaryKeys = ["groupId", "agentId"], indices = [Index("agentId")])
data class AgentGroupMemberEntity(
    val groupId: String,
    val agentId: String,
    val role: String = "MEMBER",
    val speakingMode: String = "ON_DEMAND",
    val joinedAt: Long
)

@Entity(tableName = "agent_group_messages", indices = [Index("groupId"), Index("sequence")])
data class AgentGroupMessageEntity(
    @androidx.room.PrimaryKey val id: String,
    val groupId: String,
    val sequence: Long,
    val senderType: String,
    val senderAgentId: String?,
    val content: String,
    val replyToMessageId: String?,
    val toolCallsJson: String?,
    val createdAt: Long,
    val status: String = "COMPLETED",
    val requestId: String? = null,
    val model: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val roundsCount: Int = 0,
    val usageJson: String? = null
)

@Entity(tableName = "agent_group_context_sources", indices = [Index("groupId"), Index("sourceConversationId")])
data class AgentGroupContextSourceEntity(
    @androidx.room.PrimaryKey val id: String,
    val groupId: String,
    val agentId: String?,
    val sourceConversationId: String?,
    val sourceType: String,
    val sourceRef: String,
    val importedAt: Long
)

// ── Agent Run 持久化（LOCAL_AGENT_TODO）──

@Entity(tableName = "agent_runs", indices = [Index("conversationId")])
data class AgentRunEntity(
    @androidx.room.PrimaryKey val id: String,
    val conversationId: String,
    val providerId: String,
    val tier: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: String,
    val model: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val roundsCount: Int,
    val toolCallsCount: Int,
    val error: String?
)

@Entity(tableName = "agent_tool_call_records", indices = [Index("runId")])
data class AgentToolCallRecordEntity(
    @androidx.room.PrimaryKey val id: String,
    val runId: String,
    val roundIndex: Int,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val status: String,
    val resultId: String?,
    val error: String?,
    val elapsedMillis: Long,
    val approved: Boolean,
    val timestamp: Long
)

@Entity(tableName = "agent_tool_approval_records", indices = [Index("runId")])
data class AgentToolApprovalRecordEntity(
    @androidx.room.PrimaryKey val id: String,
    val runId: String,
    val callId: String,
    val toolName: String,
    val riskLevel: String,
    val approved: Boolean,
    val reason: String?,
    val timestamp: Long
)
