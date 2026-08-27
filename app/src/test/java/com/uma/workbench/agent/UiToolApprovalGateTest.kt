package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiToolApprovalGateTest {

    private fun request(callId: String = "call-1", risk: ToolRiskLevel = ToolRiskLevel.LOCAL_WRITE) =
        ToolApprovalRequest(
            callId = callId,
            toolName = "github_clone_repository",
            argumentsJson = """{"owner":"o","repo":"r"}""",
            riskLevel = risk,
            reason = "测试请求"
        )

    @Test fun autoApproveReadOnlyNotifiesDecision() {
        runBlocking {
            val decisions = mutableListOf<Pair<ToolApprovalRequest, ToolApprovalDecision>>()
            val gate = UiToolApprovalGate(
                autoApproveReadOnly = true,
                onDecision = { req, dec -> decisions.add(req to dec) }
            )
            val decision = gate.requestApproval(request(risk = ToolRiskLevel.READ_ONLY))
            assertTrue(decision.approved)
            assertEquals(1, decisions.size)
            assertTrue(decisions.single().second.approved)
        }
    }

    @Test fun uiRespondNotifiesDecisionOnce() {
        runBlocking {
            val decisions = mutableListOf<Pair<ToolApprovalRequest, ToolApprovalDecision>>()
            val gate = UiToolApprovalGate(
                onDecision = { req, dec -> decisions.add(req to dec) }
            )
            val deferred = kotlinx.coroutines.CompletableDeferred<Unit>()
            val requester = kotlinx.coroutines.async {
                val decision = gate.requestApproval(request())
                deferred.complete(Unit)
                decision
            }
            // 等待 pending 出现后 UI 响应
            kotlinx.coroutines.withTimeout(5000) {
                while (gate.hasPending().not()) kotlinx.coroutines.delay(10)
            }
            gate.respond("call-1", approved = false, reason = "测试拒绝")
            val decision = requester.await()
            assertEquals(false, decision.approved)
            // 关键：只记录一次（respond 分支与 await 分支不得双写）
            assertEquals(1, decisions.size)
            assertEquals(false, decisions.single().second.approved)
        }
    }

    @Test fun onDecisionThrowingDoesNotAffectApproval() {
        runBlocking {
            val gate = UiToolApprovalGate(
                onDecision = { _, _ -> error("审计写入失败") }
            )
            val requester = kotlinx.coroutines.async {
                gate.requestApproval(request())
            }
            kotlinx.coroutines.withTimeout(5000) {
                while (gate.hasPending().not()) kotlinx.coroutines.delay(10)
            }
            gate.respond("call-1", approved = true)
            assertTrue(requester.await().approved)
        }
    }
}
