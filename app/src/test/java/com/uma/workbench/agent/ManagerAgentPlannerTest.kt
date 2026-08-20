package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerAgentPlannerTest {

    @Test
    fun parseDecisionExtractsIdsAndReason() {
        val response = """
            根据分析，我选择以下成员来回答：
            ```json
            {
              "selectedAgentIds": ["agent-a", "agent-b"],
              "reason": "用户的问题涉及代码分析，agent-a 擅长代码审查，agent-b 熟悉架构"
            }
            ```
        """.trimIndent()
        val validIds = listOf("agent-a", "agent-b", "agent-c")
        val result = AgentGroupReplyManagerPlanner.parseDecision(response, validIds, 2)

        assertEquals(listOf("agent-a", "agent-b"), result?.first)
        assertTrue(result!!.second.contains("代码分析"))
    }

    @Test
    fun parseDecisionFiltersInvalidIds() {
        val response = """{"selectedAgentIds": ["agent-a", "unknown-id"], "reason": "test"}"""
        val validIds = listOf("agent-a", "agent-b")
        val result = AgentGroupReplyManagerPlanner.parseDecision(response, validIds, 2)

        assertEquals(listOf("agent-a"), result?.first)
    }

    @Test
    fun parseDecisionRespectsMaxReplies() {
        val response = """{"selectedAgentIds": ["a", "b", "c"], "reason": "all"}"""
        val result = AgentGroupReplyManagerPlanner.parseDecision(response, listOf("a", "b", "c"), 2)

        assertEquals(2, result?.first?.size)
    }

    @Test
    fun parseDecisionFallbackFindsMentionedIds() {
        val response = "我认为 agent-b 最适合回答这个问题，因为..."
        val validIds = listOf("agent-a", "agent-b", "agent-c")
        val result = AgentGroupReplyManagerPlanner.parseDecision(response, validIds, 2)

        assertEquals(listOf("agent-b"), result?.first)
    }

    @Test
    fun parseDecisionReturnsNullForGarbage() {
        val response = "这是一段完全不相关的文字，没有提到任何 ID"
        val result = AgentGroupReplyManagerPlanner.parseDecision(response, listOf("agent-a"), 2)
        assertEquals(null, result)
    }

    @Test
    fun managerPlanUsesRunnerAndReturnsDecision() = runBlocking {
        val managerProfile = AgentProfileEntity("mgr", "w", "管理员", null, "管理群聊", "冷静分析", null, null, true, 1L, 1L)
        val members = listOf(
            AgentGroupMemberEntity("g", "mgr", "MANAGER", "ON_DEMAND", 1L),
            AgentGroupMemberEntity("g", "dev", "MEMBER", "ON_DEMAND", 2L),
            AgentGroupMemberEntity("g", "reviewer", "MEMBER", "ON_DEMAND", 3L)
        )
        val profiles = listOf(
            managerProfile,
            AgentProfileEntity("dev", "w", "开发者", null, "写代码", "高效", null, null, true, 1L, 1L),
            AgentProfileEntity("reviewer", "w", "审查者", null, "代码审查", "严谨", null, null, true, 1L, 1L)
        )
        val runner = AgentGroupReplyRunner { _, _ ->
            AgentGroupReplyRunnerResult(
                """{"selectedAgentIds": ["dev"], "reason": "这个问题需要开发者回答"}""",
                "req-mgr", "test-model", 1, null
            )
        }

        val decision = AgentGroupReplyManagerPlanner.plan(
            runner = runner,
            managerProfile = managerProfile,
            members = members,
            memberProfiles = profiles,
            userMessage = "这段代码有什么 bug？",
            groupContext = "[agent_group]\ngroupId=g",
            maxReplies = 2
        )

        assertEquals(listOf("dev"), decision.selectedAgentIds)
        assertEquals("mgr", decision.managerAgentId)
        assertTrue(decision.reason.contains("开发者"))
    }

    @Test
    fun managerPlanFallbackOnRunnerFailure() = runBlocking {
        val managerProfile = AgentProfileEntity("mgr", "w", "管理员", null, "管理群聊", "冷静", null, null, true, 1L, 1L)
        val members = listOf(
            AgentGroupMemberEntity("g", "mgr", "MANAGER", "ON_DEMAND", 1L),
            AgentGroupMemberEntity("g", "dev", "MEMBER", "ON_DEMAND", 2L)
        )
        val profiles = listOf(
            managerProfile,
            AgentProfileEntity("dev", "w", "开发者", null, "写代码", "高效", null, null, true, 1L, 1L)
        )
        val failingRunner = AgentGroupReplyRunner { _, _ ->
            throw RuntimeException("LLM unavailable")
        }

        val decision = AgentGroupReplyManagerPlanner.plan(
            runner = failingRunner,
            managerProfile = managerProfile,
            members = members,
            memberProfiles = profiles,
            userMessage = "测试消息",
            groupContext = "",
            maxReplies = 2
        )

        // Fallback should pick first non-manager member
        assertEquals(listOf("dev"), decision.selectedAgentIds)
        assertTrue(decision.reason.contains("回退"))
    }
}
