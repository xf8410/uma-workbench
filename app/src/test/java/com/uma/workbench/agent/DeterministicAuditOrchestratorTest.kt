package com.uma.workbench.agent

import com.uma.workbench.audit.AuditBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicAuditOrchestratorTest {

    @Test
    fun plan_createsOneTaskPerSource() {
        val orchestrator = DeterministicAuditOrchestrator(
            sourceKindResolver = { "GITHUB_REPOSITORY" }
        )
        val sourceIds = listOf("src-1", "src-2", "src-3")
        val budget = AuditBudget(runtimeSliceMillis = 300_000)

        val tasks = kotlinx.coroutines.runBlocking {
            orchestrator.plan(sourceIds, budget)
        }

        assertEquals(3, tasks.size)
        assertEquals("src-1", tasks[0].sourceId)
        assertEquals("src-2", tasks[1].sourceId)
        assertEquals("src-3", tasks[2].sourceId)
        assertEquals("GITHUB_REPOSITORY", tasks[0].kind)
    }

    @Test
    fun plan_splitsBudgetEvenly() {
        val orchestrator = DeterministicAuditOrchestrator()
        val budget = AuditBudget(runtimeSliceMillis = 300_000)

        val tasks = kotlinx.coroutines.runBlocking {
            orchestrator.plan(listOf("a", "b", "c"), budget)
        }

        assertEquals(100_000, tasks[0].budget.runtimeSliceMillis)
        assertEquals(100_000, tasks[1].budget.runtimeSliceMillis)
        assertEquals(100_000, tasks[2].budget.runtimeSliceMillis)
    }

    @Test
    fun plan_assignsUnknownKindWhenNoResolver() {
        val orchestrator = DeterministicAuditOrchestrator()
        val tasks = kotlinx.coroutines.runBlocking {
            orchestrator.plan(listOf("src-1"), AuditBudget())
        }
        assertEquals("UNKNOWN", tasks[0].kind)
    }

    @Test
    fun summarize_countsCompletedAndFailed() {
        val orchestrator = DeterministicAuditOrchestrator()
        val results = listOf(
            ChildTaskResult("t1", "COMPLETED", "found 3 files", listOf("ev1", "ev2"), null),
            ChildTaskResult("t2", "COMPLETED", "found 1 file", listOf("ev3"), null),
            ChildTaskResult("t3", "FAILED", "timeout", emptyList(), null)
        )
        val summary = kotlinx.coroutines.runBlocking {
            orchestrator.summarize(results)
        }
        assertTrue(summary.contains("任务总数：3"))
        assertTrue(summary.contains("成功：2"))
        assertTrue(summary.contains("失败：1"))
        assertTrue(summary.contains("证据总数：3"))
        assertTrue(summary.contains("t3: timeout"))
    }

    @Test
    fun summarize_includesCheckpointInfoWhenPresent() {
        val orchestrator = DeterministicAuditOrchestrator()
        val results = listOf(
            ChildTaskResult("t1", "COMPLETED", "done", listOf("ev1"), "cp-001")
        )
        val summary = kotlinx.coroutines.runBlocking {
            orchestrator.summarize(results)
        }
        assertTrue(summary.contains("检查点已保存"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun plan_rejectsEmptySourceList() {
        val orchestrator = DeterministicAuditOrchestrator()
        kotlinx.coroutines.runBlocking {
            orchestrator.plan(emptyList(), AuditBudget())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun summarize_rejectsEmptyResults() {
        val orchestrator = DeterministicAuditOrchestrator()
        kotlinx.coroutines.runBlocking {
            orchestrator.summarize(emptyList())
        }
    }
}
