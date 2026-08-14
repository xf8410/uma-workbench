package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ── 工作区 (001-040) ──
@Entity(tableName = "workspaces")
data class WorkspaceEntity(@PrimaryKey val id: String, val name: String, val baseUri: String, val createdAt: Long, val updatedAt: Long, val archived: Boolean = false, val pinned: Boolean = false, val lastOpenedAt: Long? = null)

@Entity(tableName = "workspace_projects", indices = [Index("workspaceId")])
data class ProjectEntity(@PrimaryKey val id: String, val workspaceId: String, val name: String, val label: String? = null, val color: Int? = null, val description: String? = null, val sourceUri: String? = null, val sourceType: String? = null, val pinned: Boolean = false, val sortOrder: Int = 0, val createdAt: Long)

@Entity(tableName = "recent_files", indices = [Index("workspaceId")])
data class RecentFileEntity(@PrimaryKey val id: String, val workspaceId: String, val uri: String, val name: String, val openedAt: Long)

@Entity(tableName = "open_tabs", indices = [Index("workspaceId")])
data class OpenTabEntity(@PrimaryKey val id: String, val workspaceId: String, val uri: String, val title: String, val pinned: Boolean = false, val sortOrder: Int = 0, val preview: Boolean = false)

// ── 对话 (已有，保留) ──
@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val status: String = "ACTIVE", val workspaceId: String? = null, val agentMode: String = "ASK")

@Entity(tableName = "messages", indices = [Index(value = ["conversationId", "sequence"], unique = true), Index(value = ["requestId"], unique = true)])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val runId: String?, val requestId: String?, val sequence: Long, val role: String, val content: String, val status: String = "COMPLETE", val createdAt: Long, val toolCallsJson: String? = null, val tokenCount: Int? = null, val modelUsed: String? = null)

// ── 任务 (已有，保留) ──
@Entity(tableName = "work_items", indices = [Index("parentId"), Index("sourceId")])
data class WorkItemEntity(@PrimaryKey val id: String, val kind: String, val parentId: String? = null, val sourceId: String? = null, val stage: String = "DISCOVERY", val status: String = "QUEUED", val progress: Int = 0, val checkpoint: String? = null, val attempt: Int = 0, val error: String? = null, val updatedAt: Long, val workspaceId: String? = null, val conversationId: String? = null)

// ── 审计来源 (已有，保留) ──
@Entity(tableName = "audit_sources", indices = [Index("sha256"), Index("duplicateOf")])
data class AuditSourceEntity(@PrimaryKey val id: String, val uri: String, val kind: String, val name: String, val sha256: String? = null, val version: String? = null, val purpose: String? = null, val maintenance: String? = null, val usability: String? = null, val freshness: String? = null, val duplicateOf: String? = null, val workspaceId: String? = null, val fileSize: Long? = null)

@Entity(tableName = "evidence", indices = [Index("sourceId")])
data class EvidenceEntity(@PrimaryKey val id: String, val sourceId: String, val path: String?, val commitSha: String? = null, val offset: Long?, val summary: String, val confidence: String = "CLUE", val createdAt: Long)

// ── Artifact (341-360) ──
@Entity(tableName = "artifacts", indices = [Index("workspaceId"), Index("sourceId")])
data class ArtifactEntity(@PrimaryKey val id: String, val workspaceId: String, val sourceId: String?, val conversationId: String?, val title: String, val format: String, val content: String, val tagsCsv: String? = null, val pinned: Boolean = false, val locked: Boolean = false, val sha256: String? = null, val version: Int = 1, val createdAt: Long)

// ── 知识层 (321-340) ──
@Entity(tableName = "knowledge_entries", indices = [Index("workspaceId"), Index("gameVersion")])
data class KnowledgeEntryEntity(@PrimaryKey val id: String, val workspaceId: String, val topic: String, val conclusion: String, val confidence: String = "CLUE", val evidenceCount: Int = 0, val gameVersion: String? = null, val parserVersion: String? = null, val supersededBy: String? = null, val createdAt: Long, val updatedAt: Long)

// ── 搜索 (201-220) ──
@Entity(tableName = "search_history", indices = [Index("workspaceId")])
data class SearchHistoryEntity(@PrimaryKey val id: String, val workspaceId: String, val query: String, val scope: String = "GLOBAL", val resultCount: Int = 0, val searchedAt: Long)

// ── SO 连接 (361-400) ──
@Entity(tableName = "hlpatch_snapshots", indices = [Index("capturedAt")])
data class HlpatchSnapshotEntity(@PrimaryKey val id: String, val endpoint: String, val responseBody: String, val statusCode: Int, val capturedAt: Long, val conversationId: String? = null)

// ── 同步 (已有，保留) ──
@Entity(tableName = "sync_queue", indices = [Index(value = ["idempotencyKey"], unique = true), Index("status")])
data class SyncQueueEntity(@PrimaryKey val id: String, val kind: String, val payload: String, val idempotencyKey: String, val attempts: Int = 0, val status: String = "PENDING", val updatedAt: Long)

// ── GitHub (已有，保留) ──
@Entity(tableName = "github_repositories", indices = [Index(value = ["owner", "name"], unique = true)])
data class GitHubRepositoryEntity(@PrimaryKey val id: String, val owner: String, val name: String, val defaultBranch: String, val description: String?, val isPrivate: Boolean, val isFork: Boolean, val isArchived: Boolean, val parentFullName: String?, val pushedAt: Long?, val maintenance: String = "UNKNOWN", val usability: String = "UNKNOWN", val freshness: String = "UNKNOWN")
