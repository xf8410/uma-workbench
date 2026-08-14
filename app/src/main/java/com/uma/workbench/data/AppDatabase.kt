package com.uma.workbench.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE status != 'DELETED' ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ConversationEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: ConversationEntity)
    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id") suspend fun touch(id: String, now: Long)
}
@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC") fun observe(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM messages WHERE conversationId = :conversationId") suspend fun nextSequence(conversationId: String): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: MessageEntity): Long
}
@Dao interface WorkItemDao {
    @Query("SELECT * FROM work_items ORDER BY updatedAt DESC") fun observeAll(): Flow<List<WorkItemEntity>>
    @Query("SELECT * FROM work_items WHERE id = :id LIMIT 1") suspend fun get(id: String): WorkItemEntity?
    @Query("SELECT * FROM work_items WHERE status IN ('QUEUED','RETRY_WAIT') ORDER BY updatedAt ASC LIMIT :limit") suspend fun runnable(limit: Int): List<WorkItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: WorkItemEntity)
    @Query("UPDATE work_items SET status = :status, stage = :stage, progress = :progress, checkpoint = :checkpoint, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateState(id: String, status: String, stage: String, progress: Int, checkpoint: String?, error: String?, now: Long)
}
@Dao interface AuditSourceDao {
    @Query("SELECT * FROM audit_sources ORDER BY name ASC") fun observeAll(): Flow<List<AuditSourceEntity>>
    @Query("SELECT * FROM audit_sources WHERE id = :id LIMIT 1") suspend fun get(id: String): AuditSourceEntity?
    @Query("SELECT * FROM audit_sources WHERE sha256 = :sha256 ORDER BY id LIMIT 1") suspend fun findBySha256(sha256: String): AuditSourceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: AuditSourceEntity)
}
@Dao interface EvidenceDao {
    @Query("SELECT * FROM evidence WHERE sourceId = :sourceId ORDER BY createdAt DESC") fun observe(sourceId: String): Flow<List<EvidenceEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: EvidenceEntity)
}
@Dao interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue WHERE status IN ('PENDING','RETRY_WAIT') ORDER BY updatedAt ASC LIMIT :limit") suspend fun pending(limit: Int = 20): List<SyncQueueEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: SyncQueueEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: SyncQueueEntity)
    @Query("UPDATE sync_queue SET status = :status, attempts = attempts + 1, updatedAt = :now WHERE id = :id") suspend fun mark(id: String, status: String, now: Long)
}
@Dao interface GitHubRepositoryDao {
    @Query("SELECT * FROM github_repositories ORDER BY pushedAt DESC") fun observeAll(): Flow<List<GitHubRepositoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(values: List<GitHubRepositoryEntity>)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, WorkItemEntity::class, AuditSourceEntity::class, EvidenceEntity::class, SyncQueueEntity::class, GitHubRepositoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun workItems(): WorkItemDao
    abstract fun auditSources(): AuditSourceDao
    abstract fun evidence(): EvidenceDao
    abstract fun syncQueue(): SyncQueueDao
    abstract fun githubRepositories(): GitHubRepositoryDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "uma-workbench.db").build().also { instance = it }
        }
    }
}
