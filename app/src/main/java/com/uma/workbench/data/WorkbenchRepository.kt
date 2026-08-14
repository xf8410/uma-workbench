package com.uma.workbench.data

import androidx.room.withTransaction
import com.uma.workbench.audit.SourceKind
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class QueuedImport(val sourceId: String, val workItemId: String, val duplicate: Boolean)

class WorkbenchRepository(private val database: AppDatabase) {
    fun conversations(): Flow<List<ConversationEntity>> = database.conversations().observeAll(null)
    fun messages(conversationId: String): Flow<List<MessageEntity>> = database.messages().observe(conversationId)
    fun workItems(): Flow<List<WorkItemEntity>> = database.workItems().observeAll(null)
    fun sources(): Flow<List<AuditSourceEntity>> = database.auditSources().observeAll(null)

    suspend fun createConversation(title: String = "新对话"): String {
        val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
        database.conversations().upsert(ConversationEntity(id, title, now, now)); return id
    }

    suspend fun createConversation(conv: ConversationEntity) = database.conversations().upsert(conv)

    suspend fun nextMessageSequence(conversationId: String): Long = database.messages().nextSequence(conversationId)

    suspend fun addMessage(msg: MessageEntity) = database.messages().insert(msg)

    suspend fun queueUserMessage(conversationId: String, text: String): String = database.withTransaction {
        val now = System.currentTimeMillis(); val requestId = UUID.randomUUID().toString(); val messageId = UUID.randomUUID().toString()
        val sequence = database.messages().nextSequence(conversationId)
        database.messages().insert(MessageEntity(messageId, conversationId, null, requestId, sequence, "USER", text, "QUEUED", now))
        database.syncQueue().upsert(SyncQueueEntity(UUID.randomUUID().toString(), "CHAT_REQUEST", messageId, requestId, updatedAt = now))
        database.conversations().touch(conversationId, now); requestId
    }

    suspend fun queueImportedSource(name: String, uri: String, kind: SourceKind, sha256: String): QueuedImport = database.withTransaction {
        val sourceId = UUID.randomUUID().toString(); val workItemId = UUID.randomUUID().toString()
        val duplicate = database.auditSources().findBySha256(sha256)
        database.auditSources().upsert(AuditSourceEntity(sourceId, uri, kind.name, name, sha256 = sha256, duplicateOf = duplicate?.id))
        database.workItems().upsert(WorkItemEntity(workItemId, if (duplicate == null) "SOURCE_ANALYSIS" else "DUPLICATE_REVIEW", sourceId = sourceId, stage = if (duplicate == null) "DISCOVERY" else "FINGERPRINT", updatedAt = System.currentTimeMillis()))
        QueuedImport(sourceId, workItemId, duplicate != null)
    }
}
