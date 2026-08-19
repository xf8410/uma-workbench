package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── 阶段2: 网络中断恢复 ──
@Entity(tableName = "generation_runs", indices = [Index("conversationId"), Index("status")])
data class GenerationRunEntity(
    @PrimaryKey val requestId: String,
    val conversationId: String,
    val workspaceId: String,
    val model: String,
    val systemPrompt: String?,
    val userMessage: String,
    val messagesSnapshot: String,
    val receivedText: String,
    val lastEventId: String?,
    val toolCallsJson: String?,
    val usageJson: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

// ── 阶段3: 离线消息队列 ──
@Entity(tableName = "outbound_queue", indices = [Index("conversationId"), Index("status"), Index("queueOrder")])
data class OutboundQueueEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val workspaceId: String,
    val content: String,
    val attachmentsJson: String?,
    val status: String,
    val queueOrder: Long,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

// ── 阶段10: 对话分支 ──
@Entity(tableName = "conversation_branches", indices = [Index("workspaceId"), Index("parentConversationId")])
data class ConversationBranchEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val parentConversationId: String?,
    val forkedFromMessageId: String?,
    val title: String,
    val purpose: String?,
    val createdAt: Long,
    val updatedAt: Long
)

// ── 阶段10: 对话检查点 ──
@Entity(tableName = "conversation_checkpoints", indices = [Index("conversationId")])
data class ConversationCheckpointEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String,
    val title: String,
    val summary: String,
    val goals: String?,
    val facts: String?,
    val openQuestions: String?,
    val decisions: String?,
    val evidenceRefs: String?,
    val createdAt: Long
)

// ── 阶段11: 大消息正文文件化 ──
@Entity(tableName = "message_bodies", indices = [Index("messageId", unique = true)])
data class MessageBodyEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val filePath: String,
    val characterCount: Int,
    val sha256: String,
    val preview: String
)

// ── 阶段11: Markdown 块 ──
@Entity(tableName = "message_blocks", indices = [Index("messageId")])
data class MessageBlockEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val blockIndex: Int,
    val blockType: String,
    val content: String,
    val collapsed: Boolean = false
)

// ── 阶段12: 工具结果去重 ──
@Entity(tableName = "tool_result_dedup", indices = [Index("sha256", unique = true)])
data class ToolResultDedupEntity(
    @PrimaryKey val resultId: String,
    val sha256: String,
    val filePath: String,
    val characterCount: Int,
    val toolName: String,
    val createdAt: Long
)

// ── 阶段16: 原始证据目录 ──
@Entity(tableName = "evidence_artifacts", indices = [Index("workspaceId"), Index("sha256"), Index("gameVersion")])
data class EvidenceArtifactEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val filePath: String,
    val fileType: String,
    val fileSize: Long,
    val sha256: String,
    val gameVersion: String?,
    val sourceSession: String?,
    val indexStatus: String = "PENDING",
    val createdAt: Long
)

// ── 阶段17: 证据分块索引 ──
@Entity(tableName = "evidence_chunks", indices = [Index("artifactId"), Index("sha256")])
data class EvidenceChunkEntity(
    @PrimaryKey val id: String,
    val artifactId: String,
    val chunkIndex: Int,
    val offset: Long,
    val startLine: Int,
    val endLine: Int,
    val preview: String,
    val sha256: String
)

// ── 阶段18: 端点目录 ──
@Entity(tableName = "endpoint_catalog", indices = [Index("workspaceId"), Index("path")])
data class EndpointCatalogEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val scheme: String?,
    val host: String?,
    val method: String?,
    val path: String,
    val queryParams: String?,
    val headerNames: String?,
    val jsonFields: String?,
    val statusCode: Int?,
    val firstSeen: Long,
    val lastSeen: Long,
    val callCount: Int = 1,
    val gameVersion: String?,
    val evidenceSource: String?,
    val confidence: String = "DERIVED"
)

// ── 阶段19: 知识条目 ──
@Entity(tableName = "knowledge_entries_v2", indices = [Index("workspaceId"), Index("gameVersion")])
data class KnowledgeEntryV2Entity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val topic: String,
    val category: String,
    val conclusion: String,
    val confidence: String = "CLUE",
    val gameVersion: String?,
    val sourceConversationId: String?,
    val sourceMessageId: String?,
    val openQuestions: String?,
    val status: String = "DRAFT",
    val supersededBy: String?,
    val createdAt: Long,
    val updatedAt: Long
)

// ── 阶段19: 知识证据引用 ──
@Entity(tableName = "knowledge_evidence_refs", indices = [Index("knowledgeEntryId"), Index("evidenceArtifactId")])
data class KnowledgeEvidenceRefEntity(
    @PrimaryKey val id: String,
    val knowledgeEntryId: String,
    val evidenceArtifactId: String,
    val relevance: String = "PRIMARY"
)

// ── 阶段13: 上下文预算 ──
@Entity(tableName = "context_budgets", indices = [Index("conversationId")])
data class ContextBudgetEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val contextWindow: Long?,
    val reservedOutput: Long?,
    val systemPromptTokens: Long?,
    val toolSchemaTokens: Long?,
    val safetyMargin: Long?,
    val updatedAt: Long
)
