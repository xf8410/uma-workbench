package com.uma.workbench.protocol

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Result shown after a Storage Access Framework history transfer. */
data class ProtocolHistoryTransferState(
    val running: Boolean = false,
    val operation: String? = null,
    val uri: String? = null,
    val exportedRecords: Long? = null,
    val importResult: ProtocolArchiveImportResult? = null,
    val error: String? = null
) {
    val summary: String
        get() = when {
            running -> "正在${operation ?: "处理"}；完整数据持续流式处理"
            error != null -> "${operation ?: "处理"}失败：$error"
            exportedRecords != null -> "导出完成：$exportedRecords 条完整记录"
            importResult != null -> "导入完成：${importResult.importedRecords}/${importResult.totalLines} 条；错误 ${importResult.errors.size} 条（完整错误原文已保留在结果中）"
            else -> "尚未执行 JSONL 导入或导出"
        }
}

/** Opens user-selected SAF documents and delegates lossless streaming to the archive codec. */
class ProtocolHistoryTransfer(
    private val resolver: ContentResolver,
    private val append: suspend (ProtocolHistoryRecord) -> Unit,
    private val records: suspend () -> List<ProtocolHistoryRecord>
) {
    suspend fun export(uri: Uri): Long = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(uri, "w") ?: error("无法打开导出文档：$uri")
        output.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                // The archive writes each record directly to the document and does not cap fields.
                ProtocolHistoryArchive.export(records().asSequence(), writer)
            }
        }
    }

    suspend fun import(uri: Uri): ProtocolArchiveImportResult = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: error("无法打开导入文档：$uri")
        input.use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                ProtocolHistoryArchive.import(reader) { record ->
                    kotlinx.coroutines.runBlocking { append(record) }
                }
            }
        }
    }
}
