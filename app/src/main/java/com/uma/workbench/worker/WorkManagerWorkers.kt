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
            if (kind == SourceKind.IL2CPP_METADATA) indexIl2Cpp(db, id, sourceId, Uri.parse(source.uri), item.checkpoint)
            else analyzeSinglePass(db, id, sourceId, source.name, kind, Uri.parse(source.uri))
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

    private suspend fun indexIl2Cpp(db: AppDatabase, id: String, sourceId: String, uri: Uri, encodedCheckpoint: String?): Result {
        val analysis = open(uri).use { SourceAnalyzer().analyze(SourceKind.IL2CPP_METADATA, it) as Il2CppMetadataAnalysis }
        val sourceLength = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0 } }
        val validSections = Il2CppMetadataIndexer.validateSections(analysis, sourceLength)
        require(validSections.size == analysis.sections.size) { "IL2CPP metadata contains section ranges outside the source" }
        db.il2CppIndex().upsertSections(analysis.sections.map { Il2CppSectionEntity(sourceId, it.name, it.offset, it.byteCount, analysis.version, true) })

        val indexSections = validSections.filter { it.byteCount > 0 }
        var resume = Il2CppMetadataIndexer.Checkpoint.decode(encodedCheckpoint)
        var startIndex = resume?.let { checkpoint -> indexSections.indexOfFirst { it.name == checkpoint.sectionName }.takeIf { it >= 0 } } ?: 0
        if (startIndex < 0) startIndex = 0
        val totalBytes = indexSections.sumOf { it.byteCount }.coerceAtLeast(1)

        for (sectionIndex in startIndex until indexSections.size) {
            val section = indexSections[sectionIndex]
            var nextOffset = if (resume?.sectionName == section.name) resume!!.nextOffset else 0L
            while (nextOffset < section.byteCount) {
                currentCoroutineContext().ensureActive()
                val batch = Il2CppMetadataIndexer.readSectionBatch(sourceId, { open(uri) }, section, nextOffset)
                val following = if (!batch.complete) batch.checkpoint else indexSections.getOrNull(sectionIndex + 1)?.let { Il2CppMetadataIndexer.Checkpoint(it.name, 0) }
                val completedBytes = indexSections.take(sectionIndex).sumOf { it.byteCount } + nextOffset + batch.consumedBytes
                val progress = (10 + completedBytes * 85 / totalBytes).toInt().coerceIn(10, 95)
                db.withTransaction {
                    db.il2CppIndex().upsertSectionChunks(listOf(batch.chunk))
                    if (batch.fragments.isNotEmpty()) db.il2CppIndex().upsertStringFragments(batch.fragments)
                    db.workItems().updateState(id, "RUNNING", if (section.name in Il2CppMetadataIndexer.stringSectionNames) "TEXT_INDEX" else "BINARY_INDEX", progress, following?.encode(), null, System.currentTimeMillis())
                }
                nextOffset += batch.consumedBytes
            }
            resume = null
        }

        val fragmentCount = db.il2CppIndex().stringFragmentCount(sourceId)
        val chunkCount = db.il2CppIndex().sectionChunkCount(sourceId)
        val summary = "IL2CPP global-metadata, version=${analysis.version}, sections=${analysis.nonEmptySectionCount}, verifiedChunks=$chunkCount, indexedStringFragments=$fragmentCount"
        db.withTransaction {
            db.evidence().insert(EvidenceEntity(id = UUID.randomUUID().toString(), sourceId = sourceId, path = "global-metadata.dat", offset = 0, summary = summary, confidence = "CONFIRMED", createdAt = System.currentTimeMillis()))
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, null, null, System.currentTimeMillis())
        }
        return Result.success(workDataOf("workItemId" to id, "summary" to summary))
    }

    private fun open(uri: Uri) = applicationContext.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("无法打开来源")

    private suspend fun fail(db: AppDatabase, id: String, stage: String, message: String): Result {
        db.workItems().updateState(id, "FAILED", stage, 0, null, message.take(1000), System.currentTimeMillis())
        return Result.failure(workDataOf("error" to message.take(1000)))
    }

    private fun analysisSummary(value: BinaryAnalysis?): String = when (value) {
        is ElfAnalysis -> "ELF ${if (value.is64Bit) 64 else 32}-bit, ${value.endian}-endian, machine=${value.machine}, type=${value.type}, entry=0x${value.entryPoint.toString(16)}"
        is SqliteAnalysis -> "SQLite 3, pageSize=${value.pageSize}, pageCount=${value.pageCount}, encoding=${value.textEncoding}, walHint=${value.isWalModeHint}"
        is Il2CppMetadataAnalysis -> "IL2CPP global-metadata, version=${value.version}, sections=${value.nonEmptySectionCount}"
        is ArchiveAnalysis -> "${value.archiveFormat} archive, entries=${value.entryCount}, files=${value.fileCount}, directories=${value.directoryCount}, expandedBytes=${value.expandedBytes}, unsafePaths=${value.unsafePathCount}"
        null -> "没有可用分析结果"
    }

    private companion object {
        val SUPPORTED_KINDS = setOf(SourceKind.SO, SourceKind.SQLITE, SourceKind.IL2CPP_METADATA, SourceKind.ARCHIVE)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
