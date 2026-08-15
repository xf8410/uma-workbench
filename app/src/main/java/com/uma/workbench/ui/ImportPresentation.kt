package com.uma.workbench.ui

import com.uma.workbench.data.AuditSourceEntity
import com.uma.workbench.data.WorkItemEntity

data class ImportStatusRow(
    val sourceId: String,
    val name: String,
    val kind: String,
    val fileSize: Long?,
    val sha256: String?,
    val status: String,
    val stage: String,
    val progress: Int,
    val checkpoint: String?,
    val error: String?
)

object ImportPresentation {
    fun rows(sources: List<AuditSourceEntity>, workItems: List<WorkItemEntity>): List<ImportStatusRow> {
        val itemBySource = workItems.filter { it.sourceId != null }.associateBy { it.sourceId }
        return sources.map { source ->
            val item = itemBySource[source.id]
            ImportStatusRow(
                sourceId = source.id,
                name = source.name,
                kind = source.kind,
                fileSize = source.fileSize,
                sha256 = source.sha256,
                status = item?.status ?: "NOT_QUEUED",
                stage = item?.stage ?: "DISCOVERY",
                progress = item?.progress ?: 0,
                checkpoint = item?.checkpoint,
                error = item?.error
            )
        }
    }

    fun detail(row: ImportStatusRow): String = buildString {
        appendLine("name=${row.name}")
        appendLine("sourceId=${row.sourceId}")
        appendLine("kind=${row.kind}")
        appendLine("fileSize=${row.fileSize ?: "unknown"}")
        appendLine("sha256=${row.sha256 ?: "pending"}")
        appendLine("status=${row.status}")
        appendLine("stage=${row.stage}")
        appendLine("progress=${row.progress}")
        appendLine("checkpoint=${row.checkpoint ?: "none"}")
        append("error=${row.error ?: "none"}")
    }
}
