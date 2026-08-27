package com.uma.workbench.agent

import com.uma.workbench.audit.AuditBudget

/**
 * Deterministic implementation of AuditOrchestrator that does not require an LLM.
 * Plans audit tasks by splitting sources evenly across the budget,
 * and summarizes results with simple statistics.
 */
class DeterministicAuditOrchestrator(
    private val sourceKindResolver: (String) -> String = { "UNKNOWN" }
) : AuditOrchestrator {

    override suspend fun plan(sourceIds: List<String>, budget: AuditBudget): List<ChildTask> {
        require(sourceIds.isNotEmpty()) { "至少需要一个来源 ID" }
        val perSourceMillis = budget.runtimeSliceMillis / sourceIds.size
        val perSourceBudget = AuditBudget(
            runtimeSliceMillis = maxOf(perSourceMillis, 1L)
        )
        return sourceIds.mapIndexed { index, sourceId ->
            ChildTask(
                id = "task-$index-$sourceId",
                parentId = null,
                sourceId = sourceId,
                kind = sourceKindResolver(sourceId),
                budget = perSourceBudget
            )
        }
    }

    override suspend fun summarize(results: List<ChildTaskResult>): String {
        require(results.isNotEmpty()) { "至少需要一个任务结果" }
        val succeeded = results.count { it.status == "COMPLETED" }
        val failed = results.count { it.status == "FAILED" }
        val cancelled = results.count { it.status == "CANCELLED" }
        val totalEvidence = results.sumOf { it.evidenceIds.size }
        return buildString {
            appendLine("审计摘要（确定性模式）")
            appendLine("任务总数：${results.size}")
            appendLine("成功：$succeeded")
            appendLine("失败：$failed")
            if (cancelled > 0) {
                appendLine("已取消：$cancelled")
            }
            appendLine("证据总数：$totalEvidence")
            if (failed > 0) {
                appendLine()
                appendLine("失败任务：")
                for (r in results.filter { it.status == "FAILED" }) {
                    appendLine("- ${r.taskId}: ${r.summary}")
                }
            }
            if (results.any { it.checkpoint != null }) {
                appendLine()
                appendLine("检查点已保存，可恢复未完成任务。")
            }
        }
    }
}
