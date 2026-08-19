package com.uma.workbench.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentGroupPolicyTest {
    @Test
    fun managerMustBeAMember() {
        assertFailsWith<IllegalArgumentException> {
            AgentGroupPolicy.validate(
                managerAgentId = "manager",
                memberAgentIds = listOf("researcher"),
                turnPolicy = AgentGroupPolicy.MANAGER_SELECTS
            )
        }
    }

    @Test
    fun duplicateMembersAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            AgentGroupPolicy.validate(
                managerAgentId = "manager",
                memberAgentIds = listOf("manager", "researcher", "researcher"),
                turnPolicy = AgentGroupPolicy.FREE_SPEAKING
            )
        }
    }

    @Test
    fun contextListsImportedSourcesWithoutClaimingFullHistory() {
        val group = AgentGroupEntity(
            id = "group",
            workspaceId = "workspace",
            name = "分析组",
            description = null,
            managerAgentId = "manager",
            groupPrompt = null,
            createdAt = 1L,
            updatedAt = 1L
        )
        val context = AgentGroupPolicy.buildContextInstructions(
            group,
            listOf(
                AgentProfileEntity(
                    id = "manager",
                    workspaceId = "workspace",
                    name = "管理员",
                    avatarUri = null,
                    identityMarkdown = "负责汇总",
                    soulMarkdown = "直接",
                    userMarkdown = null,
                    systemPrompt = null,
                    createdAt = 1L,
                    updatedAt = 1L
                )
            ),
            listOf(AgentGroupHistoryImport("manager", "conversation", "SUMMARY", "summary-result"))
        )
        assertTrue(context.contains("summary-result"))
        assertTrue(context.contains("不代表当前轮次已经读取了完整原文"))
    }
}
