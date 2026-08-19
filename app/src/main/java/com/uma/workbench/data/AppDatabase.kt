package com.uma.workbench.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao interface WorkspaceDao {
    @Query("SELECT * FROM workspaces WHERE archived = 0 ORDER BY pinned DESC, lastOpenedAt DESC, updatedAt DESC") fun observeAll(): Flow<List<WorkspaceEntity>>
    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1") suspend fun get(id: String): WorkspaceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(ws: WorkspaceEntity)
    @Query("UPDATE workspaces SET archived = 1, updatedAt = :now WHERE id = :id") suspend fun archive(id: String, now: Long)
    @Query("UPDATE workspaces SET lastOpenedAt = :now, updatedAt = :now WHERE id = :id") suspend fun touchOpened(id: String, now: Long)
    @Query("UPDATE workspaces SET name = :name, updatedAt = :now WHERE id = :id") suspend fun rename(id: String, name: String, now: Long)
    @Query("UPDATE workspaces SET pinned = :pinned WHERE id = :id") suspend fun setPinned(id: String, pinned: Boolean)
}
@Dao interface ProjectDao {
    @Query("SELECT * FROM workspace_projects WHERE workspaceId = :wsId ORDER BY sortOrder, name") fun observe(wsId: String): Flow<List<ProjectEntity>>
    @Query("SELECT * FROM workspace_projects WHERE workspaceId = :wsId ORDER BY sortOrder, name") suspend fun list(wsId: String): List<ProjectEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProjectEntity)
    @Delete suspend fun delete(p: ProjectEntity)
}
@Dao interface RecentFileDao {
    @Query("SELECT * FROM recent_files WHERE workspaceId = :wsId ORDER BY openedAt DESC LIMIT 20") fun observe(wsId: String): Flow<List<RecentFileEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(f: RecentFileEntity)
    @Query("DELETE FROM recent_files WHERE workspaceId = :wsId AND id NOT IN (SELECT id FROM recent_files WHERE workspaceId = :wsId ORDER BY openedAt DESC LIMIT 20)") suspend fun trim(wsId: String)
}
@Dao interface OpenTabDao {
    @Query("SELECT * FROM open_tabs WHERE workspaceId = :wsId ORDER BY pinned DESC, sortOrder") fun observe(wsId: String): Flow<List<OpenTabEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(tabs: List<OpenTabEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(tab: OpenTabEntity)
    @Query("DELETE FROM open_tabs WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM open_tabs WHERE workspaceId = :wsId") suspend fun clear(wsId: String)
}
@Dao interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE status != 'DELETED' AND (workspaceId = :wsId OR (:wsId IS NULL AND workspaceId IS NULL)) ORDER BY updatedAt DESC") fun observeAll(wsId: String?): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1") suspend fun get(id: String): ConversationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: ConversationEntity)
    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id") suspend fun touch(id: String, now: Long)
    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id") suspend fun rename(id: String, title: String, now: Long)
}
@Dao interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sequence ASC") fun observe(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM messages WHERE conversationId = :conversationId") suspend fun nextSequence(conversationId: String): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: MessageEntity): Long
}
@Dao interface WorkItemDao {
    @Query("SELECT * FROM work_items WHERE (workspaceId = :wsId OR :wsId IS NULL) ORDER BY updatedAt DESC") fun observeAll(wsId: String?): Flow<List<WorkItemEntity>>
    @Query("SELECT * FROM work_items WHERE id = :id LIMIT 1") suspend fun get(id: String): WorkItemEntity?
    @Query("SELECT * FROM work_items WHERE status IN ('QUEUED','RETRY_WAIT') ORDER BY updatedAt ASC LIMIT :limit") suspend fun runnable(limit: Int): List<WorkItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: WorkItemEntity)
    @Query("UPDATE work_items SET status = :status, stage = :stage, progress = :progress, checkpoint = :checkpoint, error = :error, updatedAt = :now WHERE id = :id") suspend fun updateState(id: String, status: String, stage: String, progress: Int, checkpoint: String?, error: String?, now: Long)
}
@Dao interface AuditSourceDao {
    @Query("SELECT * FROM audit_sources WHERE (workspaceId = :wsId OR :wsId IS NULL) ORDER BY name ASC") fun observeAll(wsId: String?): Flow<List<AuditSourceEntity>>
    @Query("SELECT * FROM audit_sources WHERE id = :id LIMIT 1") suspend fun get(id: String): AuditSourceEntity?
    @Query("SELECT * FROM audit_sources WHERE sha256 = :sha256 ORDER BY id LIMIT 1") suspend fun findBySha256(sha256: String): AuditSourceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: AuditSourceEntity)
}
@Dao interface EvidenceDao {
    @Query("SELECT * FROM evidence WHERE sourceId = :sourceId ORDER BY createdAt DESC") fun observe(sourceId: String): Flow<List<EvidenceEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: EvidenceEntity)
}
@Dao interface ArtifactDao {
    @Query("SELECT * FROM artifacts WHERE workspaceId = :wsId ORDER BY pinned DESC, createdAt DESC") fun observe(wsId: String): Flow<List<ArtifactEntity>>
    @Query("SELECT * FROM artifacts WHERE workspaceId = :wsId AND title LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 50") suspend fun search(wsId: String, q: String): List<ArtifactEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(a: ArtifactEntity)
    @Query("DELETE FROM artifacts WHERE id = :id") suspend fun delete(id: String)
}
@Dao interface KnowledgeDao {
    @Query("SELECT * FROM knowledge_entries WHERE workspaceId = :wsId AND supersededBy IS NULL ORDER BY updatedAt DESC") fun observe(wsId: String): Flow<List<KnowledgeEntryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: KnowledgeEntryEntity)
    @Query("UPDATE knowledge_entries SET supersededBy = :byId, updatedAt = :now WHERE id = :id") suspend fun supersede(id: String, byId: String, now: Long)
}
@Dao interface SearchHistoryDao {
    @Query("SELECT * FROM search_history WHERE workspaceId = :wsId ORDER BY searchedAt DESC LIMIT 30") fun observe(wsId: String): Flow<List<SearchHistoryEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: SearchHistoryEntity)
    @Query("DELETE FROM search_history WHERE workspaceId = :wsId") suspend fun clear(wsId: String)
}
@Dao interface HlpatchSnapshotDao {
    @Query("SELECT * FROM hlpatch_snapshots ORDER BY capturedAt DESC LIMIT 100") fun observeRecent(): Flow<List<HlpatchSnapshotEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: HlpatchSnapshotEntity)
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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFields(values: List<SessionFieldEntity>)
    @Query("SELECT * FROM session_records WHERE sourceId = :sourceId ORDER BY recordIndex LIMIT :limit OFFSET :offset") suspend fun records(sourceId: String, offset: Int, limit: Int): List<SessionRecordEntity>
    @Query("SELECT * FROM session_records WHERE sourceId = :sourceId AND timestampMillis IS NOT NULL ORDER BY timestampMillis, recordIndex LIMIT :limit OFFSET :offset") suspend fun timeline(sourceId: String, offset: Int, limit: Int): List<SessionRecordEntity>
    @Query("SELECT * FROM session_records WHERE timestampMillis IS NOT NULL ORDER BY timestampMillis, sourceId, recordIndex LIMIT :limit OFFSET :offset") suspend fun globalTimeline(offset: Int, limit: Int): List<SessionRecordEntity>
    @Query("SELECT * FROM session_fields WHERE sourceId = :sourceId AND recordIndex = :recordIndex ORDER BY fieldPath") suspend fun fields(sourceId: String, recordIndex: Long): List<SessionFieldEntity>
    @Query("SELECT * FROM session_fields WHERE fieldPath = :path AND normalizedValue = :value ORDER BY sourceId, recordIndex LIMIT :limit") suspend fun matchingFields(path: String, value: String, limit: Int): List<SessionFieldEntity>
    @Query("SELECT COUNT(*) FROM session_records WHERE sourceId = :sourceId") suspend fun recordCount(sourceId: String): Long
    @Query("SELECT COUNT(*) FROM session_records WHERE sourceId = :sourceId AND malformed = 1") suspend fun malformedCount(sourceId: String): Long
    @Query("SELECT COUNT(*) FROM session_fields WHERE sourceId = :sourceId") suspend fun fieldCount(sourceId: String): Long
}

@Database(entities = [
    WorkspaceEntity::class, ProjectEntity::class, RecentFileEntity::class, OpenTabEntity::class,
    ConversationEntity::class, MessageEntity::class, WorkItemEntity::class,
    AuditSourceEntity::class, EvidenceEntity::class, ArtifactEntity::class, KnowledgeEntryEntity::class,
    SearchHistoryEntity::class, HlpatchSnapshotEntity::class, SyncQueueEntity::class, GitHubRepositoryEntity::class,
    Il2CppSectionEntity::class, Il2CppSectionChunkEntity::class, Il2CppStringFragmentEntity::class,
    ArchiveEntryEntity::class, SessionRecordEntity::class, SessionFieldEntity::class,
    GenerationRunEntity::class, OutboundQueueEntity::class, ConversationBranchEntity::class,
    ConversationCheckpointEntity::class, MessageBodyEntity::class, MessageBlockEntity::class,
    ToolResultDedupEntity::class, EvidenceArtifactEntity::class, EvidenceChunkEntity::class,
    EndpointCatalogEntity::class, KnowledgeEntryV2Entity::class, KnowledgeEvidenceRefEntity::class,
    ContextBudgetEntity::class, LspServerEntity::class, LspDiagnosticEntity::class
], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workspaces(): WorkspaceDao; abstract fun projects(): ProjectDao; abstract fun recentFiles(): RecentFileDao; abstract fun openTabs(): OpenTabDao
    abstract fun conversations(): ConversationDao; abstract fun messages(): MessageDao; abstract fun workItems(): WorkItemDao
    abstract fun auditSources(): AuditSourceDao; abstract fun evidence(): EvidenceDao; abstract fun artifacts(): ArtifactDao; abstract fun knowledge(): KnowledgeDao
    abstract fun searchHistory(): SearchHistoryDao; abstract fun hlpatchSnapshots(): HlpatchSnapshotDao; abstract fun syncQueue(): SyncQueueDao; abstract fun githubRepositories(): GitHubRepositoryDao
    abstract fun il2CppIndex(): Il2CppIndexDao; abstract fun archiveIndex(): ArchiveIndexDao; abstract fun sessionIndex(): SessionIndexDao
    abstract fun generationRuns(): GenerationRunDao; abstract fun outboundQueue(): OutboundQueueDao
    abstract fun conversationBranches(): ConversationBranchDao; abstract fun conversationCheckpoints(): ConversationCheckpointDao
    abstract fun messageBodies(): MessageBodyDao; abstract fun messageBlocks(): MessageBlockDao
    abstract fun toolResultDedup(): ToolResultDedupDao; abstract fun evidenceArtifacts(): EvidenceArtifactDao
    abstract fun evidenceChunks(): EvidenceChunkDao; abstract fun endpointCatalog(): EndpointCatalogDao
    abstract fun knowledgeEntriesV2(): KnowledgeEntryV2Dao; abstract fun knowledgeEvidenceRefs(): KnowledgeEvidenceRefDao
    abstract fun contextBudgets(): ContextBudgetDao; abstract fun pagedMessages(): PagedMessageDao
    abstract fun lspServers(): LspServerDao; abstract fun lspDiagnostics(): LspDiagnosticDao

    companion object {
        private val MIGRATION_6_7 = migration(6, 7,
            "CREATE TABLE IF NOT EXISTS `workspaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `baseUri` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `lastOpenedAt` INTEGER, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `workspace_projects` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `name` TEXT NOT NULL, `label` TEXT, `color` INTEGER, `description` TEXT, `sourceUri` TEXT, `sourceType` TEXT, `pinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_workspace_projects_workspaceId` ON `workspace_projects` (`workspaceId`)",
            "CREATE TABLE IF NOT EXISTS `recent_files` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `uri` TEXT NOT NULL, `name` TEXT NOT NULL, `openedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_recent_files_workspaceId` ON `recent_files` (`workspaceId`)",
            "CREATE TABLE IF NOT EXISTS `open_tabs` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `uri` TEXT NOT NULL, `title` TEXT NOT NULL, `pinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `preview` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_open_tabs_workspaceId` ON `open_tabs` (`workspaceId`)",
            "CREATE TABLE IF NOT EXISTS `artifacts` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `sourceId` TEXT, `conversationId` TEXT, `title` TEXT NOT NULL, `format` TEXT NOT NULL, `content` TEXT NOT NULL, `tagsCsv` TEXT, `pinned` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `sha256` TEXT, `version` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_artifacts_workspaceId` ON `artifacts` (`workspaceId`)",
            "CREATE INDEX IF NOT EXISTS `index_artifacts_sourceId` ON `artifacts` (`sourceId`)",
            "CREATE TABLE IF NOT EXISTS `knowledge_entries` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `topic` TEXT NOT NULL, `conclusion` TEXT NOT NULL, `confidence` TEXT NOT NULL, `evidenceCount` INTEGER NOT NULL, `gameVersion` TEXT, `parserVersion` TEXT, `supersededBy` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_knowledge_entries_workspaceId` ON `knowledge_entries` (`workspaceId`)",
            "CREATE INDEX IF NOT EXISTS `index_knowledge_entries_gameVersion` ON `knowledge_entries` (`gameVersion`)",
            "CREATE TABLE IF NOT EXISTS `search_history` (`id` TEXT NOT NULL, `workspaceId` TEXT NOT NULL, `query` TEXT NOT NULL, `scope` TEXT NOT NULL, `resultCount` INTEGER NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_search_history_workspaceId` ON `search_history` (`workspaceId`)",
            "CREATE TABLE IF NOT EXISTS `hlpatch_snapshots` (`id` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `responseBody` TEXT NOT NULL, `statusCode` INTEGER NOT NULL, `capturedAt` INTEGER NOT NULL, `conversationId` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_hlpatch_snapshots_capturedAt` ON `hlpatch_snapshots` (`capturedAt`)",
            "ALTER TABLE conversations ADD COLUMN workspaceId TEXT",
            "ALTER TABLE conversations ADD COLUMN agentMode TEXT NOT NULL DEFAULT 'ASK'",
            "ALTER TABLE messages ADD COLUMN toolCallsJson TEXT",
            "ALTER TABLE messages ADD COLUMN tokenCount INTEGER",
            "ALTER TABLE messages ADD COLUMN modelUsed TEXT",
            "ALTER TABLE work_items ADD COLUMN workspaceId TEXT",
            "ALTER TABLE work_items ADD COLUMN conversationId TEXT",
            "ALTER TABLE audit_sources ADD COLUMN workspaceId TEXT",
            "ALTER TABLE audit_sources ADD COLUMN fileSize INTEGER"
        )
        private fun migration(from: Int, to: Int, vararg sql: String) = object : Migration(from, to) { override fun migrate(db: SupportSQLiteDatabase) { sql.forEach(db::execSQL) } }
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "uma-workbench.db").addMigrations(MIGRATION_6_7, MIGRATION_7_8).fallbackToDestructiveMigration().build().also { instance = it } }
    }
}
