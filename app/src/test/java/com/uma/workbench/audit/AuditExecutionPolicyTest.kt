package com.uma.workbench.audit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditExecutionPolicyTest {
    @Test fun budgetContainsOnlyResumableRuntimeSlice() {
        val fields = AuditBudget::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("maxFiles"))
        assertFalse(fields.contains("maxSingleFileBytes"))
        assertFalse(fields.contains("maxRuntimeSeconds"))
        assertTrue(fields.contains("runtimeSliceMillis"))
    }

    @Test fun timeBoundaryNeverInterruptsBeforeFirstDurableBatch() {
        var now = 10_000L
        val window = AuditRunWindow(AuditBudget(runtimeSliceMillis = 100L), startedAtMillis = 0L) { now }
        assertFalse(window.shouldCheckpoint(completedBatches = 0))
        assertTrue(window.shouldCheckpoint(completedBatches = 1))
    }

    @Test fun timeBoundaryOnlyRequestsCheckpointAfterElapsedSlice() {
        var now = 1_000L
        val window = AuditRunWindow(AuditBudget(runtimeSliceMillis = 500L), startedAtMillis = now) { now }
        now += 499L
        assertFalse(window.shouldCheckpoint(completedBatches = 1))
        now += 1L
        assertTrue(window.shouldCheckpoint(completedBatches = 1))
    }

    @Test fun completeLargeCheckpointRemainsResumableAndTransitionsUnchanged() {
        val completeCheckpoint = "checkpoint:" + "完整游标".repeat(20_000)
        assertTrue(AuditStageMachine.canResume(AuditStage.FILE_INDEX, completeCheckpoint))
        val transition = AuditStageMachine.transition(AuditStage.FILE_INDEX, completeCheckpoint)!!
        assertTrue(transition.checkpoint === completeCheckpoint)
        assertTrue(transition.checkpoint!!.endsWith("完整游标"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRuntimeSliceIsRejectedInsteadOfCreatingSilentPartialWork() {
        AuditBudget(runtimeSliceMillis = 0)
    }
}
