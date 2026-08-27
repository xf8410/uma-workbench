package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalGateTest {

    @Test
    fun approvalGate_autoApprovesReadOnlyByDefault() = runBlocking {
        val gate = InMemoryToolApprovalGate()
        val request = ToolApprovalRequest(
            callId = "call1",
            toolName = "read_file",
            argumentsJson = "{}",
            riskLevel = ToolRiskLevel.READ_ONLY,
            reason = "读取文件"
        )
        val decision = gate.requestApproval(request)
        assertTrue(decision.approved)
        assertNotNull(decision.reason)
    }

    @Test
    fun approvalGate_deniesRemoteWriteByDefault() = runBlocking {
        val gate = InMemoryToolApprovalGate()
        val request = ToolApprovalRequest(
            callId = "call2",
            toolName = "github_contribute_pr",
            argumentsJson = "{}",
            riskLevel = ToolRiskLevel.REMOTE_WRITE,
            reason = "创建PR"
        )
        val decision = gate.requestApproval(request)
        assertFalse(decision.approved)
    }

    @Test
    fun approvalGate_deniesLocalWriteByDefault() = runBlocking {
        val gate = InMemoryToolApprovalGate()
        val request = ToolApprovalRequest(
            callId = "call3",
            toolName = "github_clone_repository",
            argumentsJson = "{}",
            riskLevel = ToolRiskLevel.LOCAL_WRITE,
            reason = "克隆仓库"
        )
        val decision = gate.requestApproval(request)
        assertFalse(decision.approved)
    }

    @Test
    fun approvalGate_canBeConfiguredToAutoApproveLocalWrite() = runBlocking {
        val gate = InMemoryToolApprovalGate(autoApproveLocalWrite = true)
        val request = ToolApprovalRequest(
            callId = "call4",
            toolName = "github_clone_repository",
            argumentsJson = "{}",
            riskLevel = ToolRiskLevel.LOCAL_WRITE,
            reason = "克隆仓库"
        )
        val decision = gate.requestApproval(request)
        assertTrue(decision.approved)
    }

    @Test
    fun approvalGate_storesDecisionForRetrieval() = runBlocking {
        val gate = InMemoryToolApprovalGate()
        val request = ToolApprovalRequest(
            callId = "call5",
            toolName = "read_file",
            argumentsJson = "{}",
            riskLevel = ToolRiskLevel.READ_ONLY,
            reason = "读取"
        )
        gate.requestApproval(request)
        val stored = gate.getDecision("call5")
        assertNotNull(stored)
        assertEquals("call5", stored!!.callId)
    }

    @Test
    fun approvalGate_clearRemovesAllDecisions() = runBlocking {
        val gate = InMemoryToolApprovalGate()
        val request = ToolApprovalRequest(
            callId = "call6",
            toolName = "read_file",
            argumentsJson = "{}",
            riskLevel = ToolRiskLevel.READ_ONLY,
            reason = "读取"
        )
        gate.requestApproval(request)
        assertNotNull(gate.getDecision("call6"))
        gate.clear()
        assertEquals(null, gate.getDecision("call6"))
    }
}
