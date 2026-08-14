package com.uma.workbench.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @Query("UPDATE work_items SET status = :status, stage = :stage, progress = :progress, checkpoint = :checkpoint, error = :error, updatedAt = :now WHERE id = :id") suspend fun updateState(id: String, status: String, stage: String, progress: Int, checkpoint: String?, error: String?, now: Long)
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
@Dao interface Il2CppIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSections(values: List<Il2CppSectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSectionChunks(values: List<Il2CppSectionChunkEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStringFragments(values: List<Il2CppStringFragmentEntity>)
    @Query("SELECT * FROM il2cpp_sections WHERE sourceId = :sourceId ORDER BY offset") suspend fun sections(sourceId: String): List<Il2CppSectionEntity>
    @Query("SELECT * FROM il2cpp_section_chunks WHERE sourceId = :sourceId AND sectionName = :sectionName ORDER BY sectionOffset LIMIT :limit OFFSET :offset") suspend fun sectionChunks(sourceId: String, sectionName: String, offset: Int, limit: Int): List<Il2CppSectionChunkEntity>
    @Query("SELECT * FROM il2cpp_string_fragments WHERE sourceId = :sourceId ORDER BY offset LIMIT :limit OFFSET :offset") suspend fun stringFragments(sourceId: String, offset: Int, limit: Int): List<Il2CppStringFragmentEntity>
    @Query("SELECT COUNT(*) FROM il2cpp_section_chunks WHERE sourceId = :sourceId") suspend fun sectionChunkCount(sourceId: String): Long
    @Query("SELECT COUNT(*) FROM il2cpp_string_fragments WHERE sourceId = :sourceId") suspend fun stringFragmentCount(sourceId: String): Long
}
@Dao interface ArchiveIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEntries(values: List<ArchiveEntryEntity>)
    @Query("SELECT * FROM archive_entries WHERE sourceId = :sourceId ORDER BY entryIndex LIMIT :limit OFFSET :offset") suspend fun entries(sourceId: String, offset: Int, limit: Int): List<ArchiveEntryEntity>
    @Query("SELECT COUNT(*) FROM archive_entries WHERE sourceId = :sourceId") suspend fun entryCount(sourceId: String): Long
    @Query("SELECT COUNT(*) FROM archive_entries WHERE sourceId = :sourceId AND unsafePath = 1") suspend fun unsafeEntryCount(sourceId: String): Long
    @Query("SELECT COALESCE(SUM(uncompressedBytes), 0) FROM archive_entries WHERE sourceId = :sourceId") suspend fun expandedBytes(sourceId: String): Long
}
@Dao interface SessionIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRecords(values: List<SessionRecordEntity>)
    @Query("SELECT * FROM session_records WHERE sourceId = :sourceId ORDER BY recordIndex LIMIT :limit OFFSET :offset") suspend fun records(sourceId: String, offset: Int, limit: Int): List<SessionRecordEntity>
    @Query("SELECT * FROM session_records WHERE sourceId = :sourceId AND timestampMillis IS NOT NULL ORDER BY timestampMillis, recordIndex LIMIT :limit OFFSET :offset") suspend fun timeline(sourceId: String, offset: Int, limit: Int): List<SessionRecordEntity>
    @Query("SELECT COUNT(*) FROM session_records WHERE sourceId = :sourceId") suspend fun recordCount(sourceId: String): Long
    @Query("SELECT COUNT(*) FROM session_records WHERE sourceId = :sourceId AND malformed = 1") suspend fun malformedCount(sourceId: String): Long
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, WorkItemEntity::class, AuditSourceEntity::class, EvidenceEntity::class, SyncQueueEntity::class, GitHubRepositoryEntity::class, Il2CppSectionEntity::class, Il2CppSectionChunkEntity::class, Il2CppStringFragmentEntity::class, ArchiveEntryEntity::class, SessionRecordEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun workItems(): WorkItemDao
    abstract fun auditSources(): AuditSourceDao
    abstract fun evidence(): EvidenceDao
    abstract fun syncQueue(): SyncQueueDao
    abstract fun githubRepositories(): GitHubRepositoryDao
    abstract fun il2CppIndex(): Il2CppIndexDao
    abstract fun archiveIndex(): ArchiveIndexDao
    abstract fun sessionIndex(): SessionIndexDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `il2cpp_sections` (`sourceId` TEXT NOT NULL, `name` TEXT NOT NULL, `offset` INTEGER NOT NULL, `byteCount` INTEGER NOT NULL, `metadataVersion` INTEGER NOT NULL, `rangeValid` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `name`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_il2cpp_sections_sourceId` ON `il2cpp_sections` (`sourceId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `il2cpp_string_fragments` (`sourceId` TEXT NOT NULL, `offset` INTEGER NOT NULL, `byteCount` INTEGER NOT NULL, `text` TEXT NOT NULL, `continuesFromPrevious` INTEGER NOT NULL, `continuesToNext` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `offset`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_il2cpp_string_fragments_sourceId` ON `il2cpp_string_fragments` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_il2cpp_string_fragments_sourceId_text` ON `il2cpp_string_fragments` (`sourceId`, `text`)")
        } }
        private val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `il2cpp_section_chunks` (`sourceId` TEXT NOT NULL, `sectionName` TEXT NOT NULL, `sectionOffset` INTEGER NOT NULL, `absoluteOffset` INTEGER NOT NULL, `byteCount` INTEGER NOT NULL, `sha256` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `sectionName`, `sectionOffset`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_il2cpp_section_chunks_sourceId_sectionName` ON `il2cpp_section_chunks` (`sourceId`, `sectionName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_il2cpp_section_chunks_sourceId_absoluteOffset` ON `il2cpp_section_chunks` (`sourceId`, `absoluteOffset`)")
        } }
        private val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `archive_entries` (`sourceId` TEXT NOT NULL, `entryIndex` INTEGER NOT NULL, `path` TEXT NOT NULL, `directory` INTEGER NOT NULL, `uncompressedBytes` INTEGER NOT NULL, `compressedBytes` INTEGER, `crc32` INTEGER, `unsafePath` INTEGER NOT NULL, `modifiedAt` INTEGER, `type` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `entryIndex`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_entries_sourceId_path` ON `archive_entries` (`sourceId`, `path`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_archive_entries_sourceId_unsafePath` ON `archive_entries` (`sourceId`, `unsafePath`)")
        } }
        private val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `session_records` (`sourceId` TEXT NOT NULL, `recordIndex` INTEGER NOT NULL, `rawText` TEXT NOT NULL, `timestampMillis` INTEGER, `recordType` TEXT NOT NULL, `fieldCount` INTEGER NOT NULL, `malformed` INTEGER NOT NULL, PRIMARY KEY(`sourceId`, `recordIndex`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_records_sourceId_timestampMillis` ON `session_records` (`sourceId`, `timestampMillis`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_records_sourceId_recordType` ON `session_records` (`sourceId`, `recordType`)")
        } }
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "uma-workbench.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
