package com.uma.workbench.worker

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.uma.workbench.audit.*
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.EvidenceEntity
import com.uma.workbench.data.Il2CppSectionEntity
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

abstract class UmaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params)

class AuditWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("workItemId") ?: return Result.failure(workDataOf("error" to "缺少 workItemId"))
        val db = AppDatabase.get(applicationContext)
        val item = db.workItems().get(id) ?: return Result.failure(workDataOf("error" to "任务不存在"))
        val sourceId = item.sourceId ?: return fail(db, id, item.stage, "任务没有来源")
        val source = db.auditSources().get(sourceId) ?: return fail(db, id, item.stage, "来源不存在")
        if (source.duplicateOf != null) {
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, "duplicate:${source.duplicateOf}", null, System.currentTimeMillis())
            return Result.success(workDataOf("workItemId" to id, "duplicateOf" to source.duplicateOf))
        }
        val kind = runCatching { SourceKind.valueOf(source.kind) }.getOrElse { return fail(db, id, item.stage, "未知来源类型：${source.kind}") }
        if (kind !in SUPPORTED_KINDS) {
            db.workItems().updateState(id, "UNSUPPORTED", "SUMMARY", 100, "unsupported:${kind.name}", null, System.currentTimeMillis())
            return Result.success(workDataOf("workItemId" to id, "note" to "该类型暂未配置解析器"))
        }
        return try {
            when (kind) {
                SourceKind.IL2CPP_METADATA -> indexIl2Cpp(db, id, sourceId, Uri.parse(source.uri), item.checkpoint)
                SourceKind.ARCHIVE -> indexArchive(db, id, sourceId, source.name, Uri.parse(source.uri), item.checkpoint)
                SourceKind.SESSION -> indexSession(db, id, sourceId, source.name, Uri.parse(source.uri), item.checkpoint)
                else -> analyzeSinglePass(db, id, sourceId, source.name, kind, Uri.parse(source.uri))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SecurityException) {
            fail(db, id, "BINARY_INDEX", "文件读取权限已失效：${security.message}")
        } catch (invalid: IllegalArgumentException) {
            fail(db, id, "BINARY_INDEX", "文件格式无效：${invalid.message}")
        } catch (error: Exception) {
            if (runAttemptCount < 2) {
                val current = db.workItems().get(id)
                db.workItems().updateState(id, "RETRY_WAIT", current?.stage ?: "BINARY_INDEX", current?.progress ?: 0, current?.checkpoint, error.message, System.currentTimeMillis())
                Result.retry()
            } else fail(db, id, "BINARY_INDEX", error.message ?: "分析失败")
        }
    }

    private suspend fun analyzeSinglePass(db: AppDatabase, id: String, sourceId: String, name: String, kind: SourceKind, uri: Uri): Result {
        db.workItems().updateState(id, "RUNNING", "BINARY_INDEX", 50, "header", null, System.currentTimeMillis())
        val analysis = open(uri).use { SourceAnalyzer().analyze(kind, it) }
        val summary = analysisSummary(analysis)
        db.evidence().insert(EvidenceEntity(UUID.randomUUID().toString(), sourceId, name, offset = 0, summary = summary, confidence = "CONFIRMED", createdAt = System.currentTimeMillis()))
        db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, "evidence", null, System.currentTimeMillis())
        return Result.success(workDataOf("workItemId" to id, "summary" to summary))
    }

    private suspend fun indexSession(db: AppDatabase, id: String, sourceId: String, name: String, uri: Uri, encodedCheckpoint: String?): Result {
        var checkpoint = SessionIndexer.Checkpoint.decode(encodedCheckpoint)
        do {
            currentCoroutineContext().ensureActive()
            val batch = SessionIndexer.readBatch(sourceId, { open(uri) }, checkpoint)
            db.withTransaction {
                if (batch.records.isNotEmpty()) db.sessionIndex().upsertRecords(batch.records)
                if (batch.fields.isNotEmpty()) db.sessionIndex().upsertFields(batch.fields)
                db.workItems().updateState(id, "RUNNING", "TIMELINE_INDEX", if (batch.complete) 95 else 50, batch.checkpoint?.encode(), null, System.currentTimeMillis())
            }
            checkpoint = batch.checkpoint
        } while (!batch.complete)
        val count = db.sessionIndex().recordCount(sourceId)
        val malformed = db.sessionIndex().malformedCount(sourceId)
        val fields = db.sessionIndex().fieldCount(sourceId)
        val summary = "Session JSONL, indexedRecords=$count, normalizedFields=$fields, malformedRecords=$malformed"
        db.withTransaction {
            db.evidence().insert(EvidenceEntity(UUID.randomUUID().toString(), sourceId, name, offset = 0, summary = summary, confidence = "CONFIRMED", createdAt = System.currentTimeMillis()))
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, null, null, System.currentTimeMillis())
        }
        return Result.success(workDataOf("workItemId" to id, "summary" to summary))
    }

    private suspend fun indexArchive(db: AppDatabase, id: String, sourceId: String, name: String, uri: Uri, encodedCheckpoint: String?): Result {
        var checkpoint = ArchiveIndexer.Checkpoint.decode(encodedCheckpoint)
        val format = checkpoint?.format ?: ArchiveIndexer.detectFormat(open(uri))
        do {
            currentCoroutineContext().ensureActive()
            val batch = when (format) {
                ArchiveIndexer.Format.ZIP -> ArchiveIndexer.readZipBatch(sourceId, { open(uri) }, checkpoint)
                ArchiveIndexer.Format.TAR -> ArchiveIndexer.readTarBatch(sourceId, { open(uri) }, checkpoint)
            }
            db.withTransaction {
                if (batch.entries.isNotEmpty()) db.archiveIndex().upsertEntries(batch.entries)
                db.workItems().updateState(id, "RUNNING", "FILE_INDEX", if (batch.complete) 95 else 50, batch.checkpoint?.encode(), null, System.currentTimeMillis())
            }
            checkpoint = batch.checkpoint
        } while (!batch.complete)
        val count = db.archiveIndex().entryCount(sourceId)
        val unsafe = db.archiveIndex().unsafeEntryCount(sourceId)
        val expanded = db.archiveIndex().expandedBytes(sourceId)
        val summary = "${format.name} archive, indexedEntries=$count, expandedBytes=$expanded, unsafePaths=$unsafe"
        db.withTransaction {
            db.evidence().insert(EvidenceEntity(UUID.randomUUID().toString(), sourceId, name, offset = 0, summary = summary, confidence = "CONFIRMED", createdAt = System.currentTimeMillis()))
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, null, null, System.currentTimeMillis())
        }
        return Result.success(workDataOf("workItemId" to id, "summary" to summary))
    }

    private suspend fun indexIl2Cpp(db: AppDatabase, id: String, sourceId: String, uri: Uri, encodedCheckpoint: String?): Result {
        val analysis = open(uri).use { SourceAnalyzer().analyze(SourceKind.IL2CPP_METADATA, it) as Il2CppMetadataAnalysis }
        val sourceLength = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length.takeIf { it >= 0 } }
        val validSections = Il2CppMetadataIndexer.validateSections(analysis, sourceLength)
        require(validSections.size == analysis.sections.size) { "IL2CPP metadata contains section ranges outside the source" }
        db.il2CppIndex().upsertSections(analysis.sections.map { Il2CppSectionEntity(sourceId, it.name, it.offset, it.byteCount, analysis.version, true) })
        val sections = validSections.filter { it.byteCount > 0 }
        var resume = Il2CppMetadataIndexer.Checkpoint.decode(encodedCheckpoint)
        var start = resume?.let { cp -> sections.indexOfFirst { it.name == cp.sectionName }.takeIf { it >= 0 } } ?: 0
        if (start < 0) start = 0
        val total = sections.sumOf { it.byteCount }.coerceAtLeast(1)
        for (sectionIndex in start until sections.size) {
            val section = sections[sectionIndex]
            var next = if (resume?.sectionName == section.name) resume!!.nextOffset else 0L
            while (next < section.byteCount) {
                currentCoroutineContext().ensureActive()
                val batch = Il2CppMetadataIndexer.readSectionBatch(sourceId, { open(uri) }, section, next)
                val following = if (!batch.complete) batch.checkpoint else sections.getOrNull(sectionIndex + 1)?.let { Il2CppMetadataIndexer.Checkpoint(it.name, 0) }
                val completed = sections.take(sectionIndex).sumOf { it.byteCount } + next + batch.consumedBytes
                val progress = (10 + completed * 85 / total).toInt().coerceIn(10, 95)
                db.withTransaction {
                    db.il2CppIndex().upsertSectionChunks(listOf(batch.chunk))
                    if (batch.fragments.isNotEmpty()) db.il2CppIndex().upsertStringFragments(batch.fragments)
                    db.workItems().updateState(id, "RUNNING", if (section.name in Il2CppMetadataIndexer.stringSectionNames) "TEXT_INDEX" else "BINARY_INDEX", progress, following?.encode(), null, System.currentTimeMillis())
                }
                next += batch.consumedBytes
            }
            resume = null
        }
        val fragments = db.il2CppIndex().stringFragmentCount(sourceId)
        val chunks = db.il2CppIndex().sectionChunkCount(sourceId)
        val summary = "IL2CPP global-metadata, version=${analysis.version}, sections=${analysis.nonEmptySectionCount}, verifiedChunks=$chunks, indexedStringFragments=$fragments"
        db.withTransaction {
            db.evidence().insert(EvidenceEntity(UUID.randomUUID().toString(), sourceId, "global-metadata.dat", offset = 0, summary = summary, confidence = "CONFIRMED", createdAt = System.currentTimeMillis()))
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, null, null, System.currentTimeMillis())
        }
        return Result.success(workDataOf("workItemId" to id, "summary" to summary))
    }

    private fun open(uri: Uri) = applicationContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开来源")

    private suspend fun fail(db: AppDatabase, id: String, stage: String, message: String): Result {
        db.workItems().updateState(id, "FAILED", stage, 0, null, message, System.currentTimeMillis())
        return Result.failure(workDataOf("error" to message))
    }

    private fun analysisSummary(value: BinaryAnalysis?): String = when (value) {
        is ElfAnalysis -> "ELF ${if (value.is64Bit) 64 else 32}-bit, ${value.endian}-endian, machine=${value.machine}, type=${value.type}, entry=0x${value.entryPoint.toString(16)}"
        is SqliteAnalysis -> "SQLite 3, pageSize=${value.pageSize}, pageCount=${value.pageCount}, encoding=${value.textEncoding}, walHint=${value.isWalModeHint}"
        is Il2CppMetadataAnalysis -> "IL2CPP global-metadata, version=${value.version}, sections=${value.nonEmptySectionCount}"
        is ArchiveAnalysis -> "${value.archiveFormat} archive, entries=${value.entryCount}, files=${value.fileCount}, directories=${value.directoryCount}, expandedBytes=${value.expandedBytes}, unsafePaths=${value.unsafePathCount}"
        null -> "没有可用分析结果"
    }

    private companion object {
        val SUPPORTED_KINDS = setOf(SourceKind.SO, SourceKind.SQLITE, SourceKind.IL2CPP_METADATA, SourceKind.ARCHIVE, SourceKind.SESSION)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

class DiaryWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result {
        val agentId = inputData.getString("agentId")
        val db = com.uma.workbench.agent.AgentPartnerDatabase.get(applicationContext)
        val store = com.uma.workbench.agent.AgentPartnerStore(db)
        val catalogStore = com.uma.workbench.agent.AiProviderCatalogStore(applicationContext)
        val catalog = catalogStore.load()
        val profile = catalog.defaultModel?.let { mid ->
            catalog.providers.firstOrNull { p -> mid in p.models }
        }
        if (profile == null) {
            return Result.failure(workDataOf("error" to "未配置 AI 模型"))
        }
        val provider = com.uma.workbench.agent.CatalogAiStreamingProvider { profile }
        val targetAgentIds = if (agentId != null) {
            listOf(agentId)
        } else {
            db.profiles().getAllEnabled().map { it.id }
        }
        if (targetAgentIds.isEmpty()) {
            return Result.success(workDataOf("note" to "没有启用的伙伴"))
        }
        var diaryCount = 0
        val today = java.time.LocalDate.now()
        targetAgentIds.forEach { targetAgentId ->
            try {
                val agentProfile = db.profiles().get(targetAgentId) ?: return@forEach
                val memberEntries = db.groups().groupsContainingMember(targetAgentId)
                if (memberEntries.isEmpty()) return@forEach
                val conversationText = buildString {
                    memberEntries.forEach { group ->
                        val messages = db.groups().getRecentMessages(group.id, 20)
                        if (messages.isNotEmpty()) {
                            appendLine("## 群聊：${group.name}")
                            messages.forEach { msg ->
                                val sender = when (msg.senderType) {
                                    "USER" -> "用户"
                                    "AGENT" -> if (msg.senderAgentId == targetAgentId) agentProfile.name else (msg.senderAgentId ?: "Agent")
                                    else -> "系统"
                                }
                                appendLine("$sender: ${msg.content.take(500)}")
                            }
                            appendLine()
                        }
                    }
                }
                if (conversationText.isBlank()) return@forEach
                val prompt = com.uma.workbench.agent.AgentDiaryPromptBuilder.build(agentProfile, today, conversationText)
                val request = com.uma.workbench.agent.AiGenerationRequest(
                    requestId = java.util.UUID.randomUUID().toString(),
                    messages = listOf(com.uma.workbench.agent.AiPromptMessage(role = "user", completeContent = prompt)),
                    model = catalog.defaultModel,
                    tools = null
                )
                var fullText = ""
                provider.stream(request).collect { event ->
                    when (event) {
                        is com.uma.workbench.agent.AiStreamEvent.TextDelta -> fullText += event.completeDelta
                        else -> {}
                    }
                }
                if (fullText.isNotBlank()) {
                    val title = "${agentProfile.name} 的日记 - $today"
                    val content = fullText.trim()
                    store.saveDiary(
                        agentId = targetAgentId,
                        date = today,
                        title = title,
                        content = content,
                        sourceConversationId = null,
                        sourceMessageRange = null,
                        status = "DRAFT"
                    )
                    diaryCount++
                }
            } catch (e: Exception) {
                // Continue with next agent
            }
        }
        return Result.success(workDataOf("diaryCount" to diaryCount))
    }
}
