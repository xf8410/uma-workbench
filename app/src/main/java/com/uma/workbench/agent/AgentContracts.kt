package com.uma.workbench.agent

import com.uma.workbench.audit.AuditBudget

data class ChildTask(val id: String, val parentId: String?, val sourceId: String, val kind: String, val budget: AuditBudget)
data class ChildTaskResult(val taskId: String, val status: String, val summary: String, val evidenceIds: List<String>, val checkpoint: String?)

interface ChildAgent {
    suspend fun run(task: ChildTask, checkpoint: String?): ChildTaskResult
}

interface AuditOrchestrator {
    suspend fun plan(sourceIds: List<String>, budget: AuditBudget): List<ChildTask>
    suspend fun summarize(results: List<ChildTaskResult>): String
}
