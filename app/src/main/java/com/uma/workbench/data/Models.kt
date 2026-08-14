package com.uma.workbench.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long, val status: String = "ACTIVE")

@Entity(tableName = "messages", primaryKeys = ["id"])
data class MessageEntity(val id: String, val conversationId: String, val runId: String?, val requestId: String?, val sequence: Long, val role: String, val content: String, val status: String = "COMPLETE", val createdAt: Long)

@Entity(tableName = "work_items")
data class WorkItemEntity(@PrimaryKey val id: String, val kind: String, val status: String = "QUEUED", val progress: Int = 0, val checkpoint: String? = null, val error: String? = null, val updatedAt: Long)

@Entity(tableName = "audit_sources")
data class AuditSourceEntity(@PrimaryKey val id: String, val uri: String, val kind: String, val name: String, val sha256: String? = null, val version: String? = null, val maintenance: String? = null, val freshness: String? = null, val duplicateOf: String? = null)

@Entity(tableName = "evidence")
data class EvidenceEntity(@PrimaryKey val id: String, val sourceId: String, val path: String?, val offset: Long?, val summary: String, val createdAt: Long)

@Entity(tableName = "sync_queue")
data class SyncQueueEntity(@PrimaryKey val id: String, val kind: String, val payload: String, val idempotencyKey: String, val attempts: Int = 0, val status: String = "PENDING", val updatedAt: Long)
