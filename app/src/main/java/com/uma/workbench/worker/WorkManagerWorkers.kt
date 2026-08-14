package com.uma.workbench.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.uma.workbench.audit.*
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.EvidenceEntity
import java.io.FileNotFoundException
import java.util.UUID

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
        if (kind != SourceKind.SO && kind != SourceKind.SQLITE) {
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, "unsupported:${kind.name}", null, System.currentTimeMillis())
            return Result.success(workDataOf("workItemId" to id, "note" to "该类型暂未配置解析器"))
        }
        db.workItems().updateState(id, "RUNNING", "BINARY_INDEX", 50, "header", null, System.currentTimeMillis())
        return try {
            val analysis = applicationContext.contentResolver.openInputStream(Uri.parse(source.uri))?.use { SourceAnalyzer().analyze(kind, it) }
                ?: throw FileNotFoundException("无法打开来源")
            val summary = analysisSummary(analysis)
            db.evidence().insert(EvidenceEntity(UUID.randomUUID().toString(), sourceId, source.name, offset = 0, summary = summary, confidence = "CONFIRMED", createdAt = System.currentTimeMillis()))
            db.workItems().updateState(id, "COMPLETE", "SUMMARY", 100, "evidence", null, System.currentTimeMillis())
            Result.success(workDataOf("workItemId" to id, "summary" to summary))
        } catch (security: SecurityException) {
            fail(db, id, "BINARY_INDEX", "文件读取权限已失效：${security.message}")
        } catch (invalid: IllegalArgumentException) {
            fail(db, id, "BINARY_INDEX", "文件格式无效：${invalid.message}")
        } catch (error: Exception) {
            if (runAttemptCount < 2) { db.workItems().updateState(id, "RETRY_WAIT", "BINARY_INDEX", 50, "header", error.message, System.currentTimeMillis()); Result.retry() }
            else fail(db, id, "BINARY_INDEX", error.message ?: "分析失败")
        }
    }

    private suspend fun fail(db: AppDatabase, id: String, stage: String, message: String): Result {
        db.workItems().updateState(id, "FAILED", stage, 0, null, message.take(1000), System.currentTimeMillis())
        return Result.failure(workDataOf("error" to message.take(1000)))
    }

    private fun analysisSummary(value: BinaryAnalysis?): String = when (value) {
        is ElfAnalysis -> "ELF ${if (value.is64Bit) 64 else 32}-bit, ${value.endian}-endian, machine=${value.machine}, type=${value.type}, entry=0x${value.entryPoint.toString(16)}"
        is SqliteAnalysis -> "SQLite 3, pageSize=${value.pageSize}, pageCount=${value.pageCount}, encoding=${value.textEncoding}, walHint=${value.isWalModeHint}"
        null -> "没有可用分析结果"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : UmaWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
