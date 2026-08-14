package com.uma.workbench.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeAll(): Flow<List<ConversationEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: ConversationEntity)
}
@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC") fun observe(conversationId: String): Flow<List<MessageEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: MessageEntity)
}
@Dao interface WorkItemDao {
    @Query("SELECT * FROM work_items ORDER BY updatedAt DESC") fun observeAll(): Flow<List<WorkItemEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: WorkItemEntity)
}
@Dao interface AuditSourceDao {
    @Query("SELECT * FROM audit_sources ORDER BY name ASC") fun observeAll(): Flow<List<AuditSourceEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: AuditSourceEntity)
}
@Dao interface SyncQueueDao {
    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY updatedAt ASC") suspend fun pending(): List<SyncQueueEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: SyncQueueEntity)
    @Query("UPDATE sync_queue SET status = :status, attempts = attempts + 1, updatedAt = :now WHERE id = :id") suspend fun mark(id: String, status: String, now: Long)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, WorkItemEntity::class, AuditSourceEntity::class, EvidenceEntity::class, SyncQueueEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun workItems(): WorkItemDao
    abstract fun auditSources(): AuditSourceDao
    abstract fun syncQueue(): SyncQueueDao
    companion object { @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "uma-workbench.db").build().also { instance = it } }
    }
}
