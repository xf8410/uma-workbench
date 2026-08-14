package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val status: String = "ACTIVE")

@Entity(tableName = "messages", indices = [Index(value = ["conversationId", "sequence"], unique = true), Index(value = ["requestId"], unique = true)])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val runId: String?, val requestId: String?, val sequence: Long, val role: String, val content: String, val status: String = "COMPLETE", val createdAt: Long)

@Entity(tableName = "work_items", indices = [Index("parentId"), Index("sourceId")])
data class WorkItemEntity(@PrimaryKey val id: String, val kind: String, val parentId: String? = null, val sourceId: String? = null, val stage: String = "DISCOVERY", val status: String = "QUEUED", val progress: Int = 0, val checkpoint: String? = null, val attempt: Int = 0, val error: String? = null, val updatedAt: Long)

@Entity(tableName = "audit_sources", indices = [Index("sha256"), Index("duplicateOf")])
data class AuditSourceEntity(@PrimaryKey val id: String, val uri: String, val kind: String, val name: String, val sha256: String? = null, val version: String? = null, val purpose: String? = null, val maintenance: String? = null, val usability: String? = null, val freshness: String? = null, val duplicateOf: String? = null)

@Entity(tableName = "evidence", indices = [Index("sourceId")])
data class EvidenceEntity(@PrimaryKey val id: String, val sourceId: String, val path: String?, val commitSha: String? = null, val offset: Long?, val summary: String, val confidence: String = "CLUE", val createdAt: Long)

@Entity(tableName = "sync_queue", indices = [Index(value = ["idempotencyKey"], unique = true), Index("status")])
data class SyncQueueEntity(@PrimaryKey val id: String, val kind: String, val payload: String, val idempotencyKey: String, val attempts: Int = 0, val status: String = "PENDING", val updatedAt: Long)

@Entity(tableName = "github_repositories", indices = [Index(value = ["owner", "name"], unique = true)])
data class GitHubRepositoryEntity(@PrimaryKey val id: String, val owner: String, val name: String, val defaultBranch: String, val description: String?, val isPrivate: Boolean, val isFork: Boolean, val isArchived: Boolean, val parentFullName: String?, val pushedAt: Long?, val maintenance: String = "UNKNOWN", val usability: String = "UNKNOWN", val freshness: String = "UNKNOWN")
