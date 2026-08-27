package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGroupTurnPlannerTest {
    private val members = listOf(
        AgentGroupMemberEntity("g", "manager", "MANAGER", "ON_DEMAND", 1L),
        AgentGroupMemberEntity("g", "first", "MEMBER", "ON_DEMAND", 2L),
        AgentGroupMemberEntity("g", "second", "MEMBER", "ON_DEMAND", 3L)
    )

    @Test
    fun managerSelectsAtMostOneDefaultMember() {
        val decision = AgentGroupTurnPlanner.plan(group(AgentGroupPolicy.MANAGER_SELECTS), members, "分析")
        assertEquals(listOf("first"), decision.selectedAgentIds)
    }

    @Test
    fun freeSpeakingIsBoundedToTwoMembers() {
        val decision = AgentGroupTurnPlanner.plan(group(AgentGroupPolicy.FREE_SPEAKING), members, "分析")
        assertEquals(listOf("first", "second"), decision.selectedAgentIds)
    }

    @Test
    fun userDirectedIgnoresUnknownAgentIds() {
        val decision = AgentGroupTurnPlanner.plan(group(AgentGroupPolicy.USER_DIRECTED), members, "分析", listOf("unknown", "second"))
        assertEquals(listOf("second"), decision.selectedAgentIds)
    }

    private fun group(policy: String) = AgentGroupEntity(
        id = "g",
        workspaceId = "w",
        name = "分析组",
        description = null,
        managerAgentId = "manager",
        turnPolicy = policy,
        groupPrompt = null,
        createdAt = 1L,
        updatedAt = 1L
    )
}
