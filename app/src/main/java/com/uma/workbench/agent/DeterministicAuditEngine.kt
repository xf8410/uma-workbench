package com.uma.workbench.agent

import android.content.ContentResolver
import android.net.Uri
import com.uma.workbench.audit.ArchiveAnalysis
import com.uma.workbench.audit.AuditBudget
import com.uma.workbench.audit.BinaryAnalysis
import com.uma.workbench.audit.ElfAnalysis
import com.uma.workbench.audit.Il2CppMetadataAnalysis
import com.uma.workbench.audit.SourceAnalyzer
import com.uma.workbench.audit.SourceKind
import com.uma.workbench.audit.SqliteAnalysis
import com.uma.workbench.data.AppDatabase
import com.uma.workbench.data.EvidenceEntity
import java.util.UUID

/**
 * 确定性审计执行引擎：把 DeterministicAuditOrchestrator 的任务计划落到真实来源分析。
 * 全程零模型调用——SourceAnalyzer / BinaryAnalyzers 的纯确定性输出，证据落 evidence 表。
 * 这是 LOCAL_AGENT_TODO「无模型时按确定性工作流运行」的实际接线。
 */
class DeterministicAuditEngine(
    private val contentResolver: ContentResolver,
    private val database: AppDatabase
) {
    data class AuditOutcome(
        val summary: String,
        val results: List<ChildTaskResult>,
        val evidenceCount: Int
    )

    /**
     * 完整审计流程：plan（均分预算）→ 逐任务确定性分析 + 证据落库 → summarize。
     * 任一任务失败不影响其余任务；失败详情保留在结果摘要中。
     */
    suspend fun runAudit(
        sourceIds: List<String>,
        budget: AuditBudget = AuditBudget()
    ): AuditOutcome {
        require(sourceIds.isNotEmpty()) { "至少选择一个审计来源" }
        // sourceKindResolver 是非 suspend 回调：先同步加载全部 kind，避免在 lambda 内触发挂起调用。
        val kindMap = mutableMapOf<String, String>()
        for (id in sourceIds) {
            kindMap[id] = runCatching { resolveKind(database.auditSources().get(id)?.kind ?: "").name }.getOrDefault("UNKNOWN")
        }
        val orchestrator = DeterministicAuditOrchestrator { id -> kindMap[id] ?: "UNKNOWN" }
        val tasks = orchestrator.plan(sourceIds, budget)
        val results = tasks.map { task -> runChildTask(task) }
        val summary = orchestrator.summarize(results)
        return AuditOutcome(summary, results, results.sumOf { it.evidenceIds.size })
    }

    private suspend fun runChildTask(task: ChildTask): ChildTaskResult {
        val source = database.auditSources().get(task.sourceId)
            ?: return ChildTaskResult(task.id, "FAILED", "来源不存在: ${task.sourceId}", emptyList(), null)
        return try {
            val kind = resolveKind(source.kind)
            val analysisSummary = contentResolver.openInputStream(Uri.parse(source.uri))?.use { input ->
                describeAnalysis(kind, SourceAnalyzer().analyze(kind, input))
            } ?: error("无法打开来源流")
            val evidenceId = persistEvidence(source.id, analysisSummary)
            ChildTaskResult(task.id, "COMPLETED", "${source.name} · $analysisSummary", listOf(evidenceId), null)
        } catch (e: Exception) {
            ChildTaskResult(task.id, "FAILED", "${source.name}: ${e.message ?: e.javaClass.simpleName}", emptyList(), null)
        }
    }

    /** 来源分析结果 → 人类可读摘要；null 表示该类型暂无确定性分析器。 */
    private fun describeAnalysis(kind: SourceKind, analysis: BinaryAnalysis?): String {
        if (analysis == null) return "类型 $kind 暂无确定性分析器，已登记来源待接入"
        return when (analysis) {
            is ElfAnalysis ->
                "ELF ${if (analysis.is64Bit) "64位" else "32位"} ${analysis.endian}端 machine=${analysis.machine} type=${analysis.type} 入口=0x${analysis.entryPoint.toString(16)}"
            is SqliteAnalysis ->
                "SQLite 页大小=${analysis.pageSize} 页数=${analysis.pageCount} 编码=${analysis.textEncoding}${if (analysis.isWalModeHint) " WAL模式" else ""}"
            is Il2CppMetadataAnalysis ->
                "IL2CPP metadata v${analysis.version} 共${analysis.sections.size}个段（非空 ${analysis.nonEmptySectionCount}）"
            is ArchiveAnalysis ->
                "${analysis.archiveFormat} 共${analysis.entryCount}项（文件${analysis.fileCount}/目录${analysis.directoryCount}）展开${analysis.expandedBytes}字节${if (analysis.unsafePathCount > 0) " ⚠️不安全路径${analysis.unsafePathCount}个" else ""}"
        }.let { text -> if (analysis.warnings.isEmpty()) text else "$text · 警告: ${analysis.warnings.joinToString("; ")}" }
    }

    private fun resolveKind(stored: String): SourceKind =
        runCatching { SourceKind.valueOf(stored) }.getOrDefault(SourceKind.LOG)

    private suspend fun persistEvidence(sourceId: String, summary: String): String {
        val id = UUID.randomUUID().toString()
        database.evidence().insert(
            EvidenceEntity(
                id = id,
                sourceId = sourceId,
                path = null,
                commitSha = null,
                offset = null,
                summary = summary.take(2_000),
                confidence = "CONFIRMED",
                createdAt = System.currentTimeMillis()
            )
        )
        return id
    }
}
