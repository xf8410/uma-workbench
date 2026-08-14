package com.uma.workbench.data

import androidx.room.withTransaction
import com.uma.workbench.audit.SourceKind
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class WorkbenchRepository(private val database: AppDatabase) {
    fun conversations(): Flow<List<ConversationEntity>> = database.conversations().observeAll()
    fun messages(conversationId: String): Flow<List<MessageEntity>> = database.messages().observe(conversationId)
    fun workItems(): Flow<List<WorkItemEntity>> = database.workItems().observeAll()
    fun sources(): Flow<List<AuditSourceEntity>> = database.auditSources().observeAll()

    suspend fun createConversation(title: String = "新对话"): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        database.conversations().upsert(ConversationEntity(id, title, now, now))
        return id
    }

    suspend fun queueUserMessage(conversationId: String, text: String): String = database.withTransaction {
        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val sequence = database.messages().nextSequence(conversationId)
        database.messages().insert(MessageEntity(messageId, conversationId, null, requestId, sequence, "USER", text, "QUEUED", now))
        database.syncQueue().upsert(SyncQueueEntity(UUID.randomUUID().toString(), "CHAT_REQUEST", messageId, requestId, updatedAt = now))
        database.conversations().touch(conversationId, now)
        requestId
    }

    suspend fun queueImportedSource(name: String, uri: String, kind: SourceKind, sha256: String): String = database.withTransaction {
        val id = UUID.randomUUID().toString()
        val duplicate = database.auditSources().findBySha256(sha256)
        database.auditSources().upsert(
            AuditSourceEntity(id = id, uri = uri, kind = kind.name, name = name, sha256 = sha256, duplicateOf = duplicate?.id)
        )
        database.workItems().upsert(
            WorkItemEntity(
                id = UUID.randomUUID().toString(),
                kind = if (duplicate == null) "SOURCE_DISCOVERY" else "DUPLICATE_REVIEW",
                sourceId = id,
                stage = if (duplicate == null) "DISCOVERY" else "FINGERPRINT",
                updatedAt = System.currentTimeMillis()
            )
        )
        id
    }
}
