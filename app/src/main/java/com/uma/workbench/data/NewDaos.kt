package com.uma.workbench.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface GenerationRunDao {
    @Query("SELECT * FROM generation_runs WHERE status IN ('WAITING_FOR_NETWORK','RESUMING','GENERATING') ORDER BY updatedAt DESC LIMIT 1") suspend fun activeRun(): GenerationRunEntity?
    @Query("SELECT * FROM generation_runs WHERE requestId = :id LIMIT 1") suspend fun get(id: String): GenerationRunEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(run: GenerationRunEntity)
    @Query("UPDATE generation_runs SET receivedText = :text, lastEventId = :eventId, updatedAt = :now WHERE requestId = :id") suspend fun updateProgress(id: String, text: String, eventId: String?, now: Long)
    @Query("UPDATE generation_runs SET status = :status, updatedAt = :now WHERE requestId = :id") suspend fun updateStatus(id: String, status: String, now: Long)
    @Query("DELETE FROM generation_runs WHERE status IN ('COMPLETED','CANCELLED','FAILED') AND updatedAt < :before") suspend fun cleanup(before: Long)
}

@Dao interface OutboundQueueDao {
    @Query("SELECT * FROM outbound_queue WHERE status IN ('QUEUED_OFFLINE','QUEUED_AFTER_CURRENT') ORDER BY queueOrder ASC") fun observePending(): Flow<List<OutboundQueueEntity>>
    @Query("SELECT * FROM outbound_queue WHERE conversationId = :convId AND status IN ('QUEUED_OFFLINE','QUEUED_AFTER_CURRENT') ORDER BY queueOrder ASC") suspend fun pendingFor(convId: String): List<OutboundQueueEntity>
    @Query("SELECT * FROM outbound_queue WHERE status = 'READY' ORDER BY queueOrder ASC LIMIT 1") suspend fun nextReady(): OutboundQueueEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: OutboundQueueEntity)
    @Query("UPDATE outbound_queue SET status = :status, attempts = attempts + 1, lastError = :error WHERE id = :id") suspend fun mark(id: String, status: String, error: String?)
    @Query("DELETE FROM outbound_queue WHERE status IN ('SENT','CANCELLED')") suspend fun cleanup()
    @Query("SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM outbound_queue WHERE conversationId = :convId") suspend fun nextOrder(convId: String): Long
}

@Dao interface ConversationBranchDao {
    @Query("SELECT * FROM conversation_branches WHERE workspaceId = :wsId ORDER BY updatedAt DESC") fun observe(wsId: String): Flow<List<ConversationBranchEntity>>
    @Query("SELECT * FROM conversation_branches WHERE parentConversationId = :parentId") suspend fun children(parentId: String): List<ConversationBranchEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(branch: ConversationBranchEntity)
    @Query("UPDATE conversation_branches SET title = :title, updatedAt = :now WHERE id = :id") suspend fun rename(id: String, title: String, now: Long)
}

@Dao interface ConversationCheckpointDao {
    @Query("SELECT * FROM conversation_checkpoints WHERE conversationId = :convId ORDER BY createdAt DESC") fun observe(convId: String): Flow<List<ConversationCheckpointEntity>>
    @Query("SELECT * FROM conversation_checkpoints WHERE id = :id LIMIT 1") suspend fun get(id: String): ConversationCheckpointEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(cp: ConversationCheckpointEntity)
    @Query("DELETE FROM conversation_checkpoints WHERE id = :id") suspend fun delete(id: String)
}

@Dao interface MessageBodyDao {
    @Query("SELECT * FROM message_bodies WHERE messageId = :msgId LIMIT 1") suspend fun get(msgId: String): MessageBodyEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(body: MessageBodyEntity)
    @Query("DELETE FROM message_bodies WHERE messageId = :msgId") suspend fun delete(msgId: String)
}

@Dao interface MessageBlockDao {
    @Query("SELECT * FROM message_blocks WHERE messageId = :msgId ORDER BY blockIndex") suspend fun blocks(msgId: String): List<MessageBlockEntity>
    @Query("SELECT * FROM message_blocks WHERE messageId = :msgId AND blockIndex BETWEEN :start AND :end ORDER BY blockIndex") suspend fun blockRange(msgId: String, start: Int, end: Int): List<MessageBlockEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(blocks: List<MessageBlockEntity>)
    @Query("DELETE FROM message_blocks WHERE messageId = :msgId") suspend fun delete(msgId: String)
}

@Dao interface ToolResultDedupDao {
    @Query("SELECT * FROM tool_result_dedup WHERE sha256 = :hash LIMIT 1") suspend fun findByHash(hash: String): ToolResultDedupEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: ToolResultDedupEntity)
}

@Dao interface EvidenceArtifactDao {
    @Query("SELECT * FROM evidence_artifacts WHERE workspaceId = :wsId ORDER BY createdAt DESC") fun observe(wsId: String): Flow<List<EvidenceArtifactEntity>>
    @Query("SELECT * FROM evidence_artifacts WHERE sha256 = :hash LIMIT 1") suspend fun findByHash(hash: String): EvidenceArtifactEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(artifact: EvidenceArtifactEntity)
    @Query("UPDATE evidence_artifacts SET indexStatus = :status WHERE id = :id") suspend fun updateIndexStatus(id: String, status: String)
}

@Dao interface EvidenceChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(chunks: List<EvidenceChunkEntity>)
    @Query("SELECT * FROM evidence_chunks WHERE artifactId = :artifactId ORDER BY chunkIndex LIMIT :limit OFFSET :offset") suspend fun chunks(artifactId: String, offset: Int, limit: Int): List<EvidenceChunkEntity>
    @Query("SELECT * FROM evidence_chunks WHERE preview LIKE '%' || :q || '%' LIMIT 50") suspend fun searchPreview(q: String): List<EvidenceChunkEntity>
    @Query("SELECT COUNT(*) FROM evidence_chunks WHERE artifactId = :artifactId") suspend fun count(artifactId: String): Long
}

@Dao interface EndpointCatalogDao {
    @Query("SELECT * FROM endpoint_catalog WHERE workspaceId = :wsId ORDER BY lastSeen DESC") fun observe(wsId: String): Flow<List<EndpointCatalogEntity>>
    @Query("SELECT * FROM endpoint_catalog WHERE workspaceId = :wsId AND path = :path LIMIT 1") suspend fun find(wsId: String, path: String): EndpointCatalogEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: EndpointCatalogEntity)
    @Query("UPDATE endpoint_catalog SET lastSeen = :now, callCount = callCount + 1 WHERE id = :id") suspend fun touch(id: String, now: Long)
}

@Dao interface KnowledgeEntryV2Dao {
    @Query("SELECT * FROM knowledge_entries_v2 WHERE workspaceId = :wsId AND supersededBy IS NULL ORDER BY updatedAt DESC") fun observe(wsId: String): Flow<List<KnowledgeEntryV2Entity>>
    @Query("SELECT * FROM knowledge_entries_v2 WHERE id = :id LIMIT 1") suspend fun get(id: String): KnowledgeEntryV2Entity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(entry: KnowledgeEntryV2Entity)
    @Query("DELETE FROM knowledge_entries_v2 WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE knowledge_entries_v2 SET supersededBy = :byId, updatedAt = :now WHERE id = :id") suspend fun supersede(id: String, byId: String, now: Long)
    @Query("SELECT * FROM knowledge_entries_v2 WHERE workspaceId = :wsId AND (topic LIKE '%' || :q || '%' OR conclusion LIKE '%' || :q || '%') AND supersededBy IS NULL ORDER BY updatedAt DESC LIMIT 50") suspend fun search(wsId: String, q: String): List<KnowledgeEntryV2Entity>
}

@Dao interface KnowledgeEvidenceRefDao {
    @Query("SELECT * FROM knowledge_evidence_refs WHERE knowledgeEntryId = :entryId") suspend fun refs(entryId: String): List<KnowledgeEvidenceRefEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(ref: KnowledgeEvidenceRefEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(refs: List<KnowledgeEvidenceRefEntity>)
    @Query("DELETE FROM knowledge_evidence_refs WHERE knowledgeEntryId = :entryId") suspend fun deleteForEntry(entryId: String)
}

@Dao interface ContextBudgetDao {
    @Query("SELECT * FROM context_budgets WHERE conversationId = :convId LIMIT 1") suspend fun get(convId: String): ContextBudgetEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(budget: ContextBudgetEntity)
}

// ── 消息分页 (阶段7) ──
@Dao interface PagedMessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY sequence DESC LIMIT :limit") suspend fun recentMessages(convId: String, limit: Int): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE conversationId = :convId AND sequence < :beforeSequence ORDER BY sequence DESC LIMIT :limit") suspend fun messagesBefore(convId: String, beforeSequence: Long, limit: Int): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :convId") suspend fun count(convId: String): Long
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteById(id: String): Int
}
