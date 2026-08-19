package com.uma.workbench.agent

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPartnerStoreTest {
    @Test
    fun diaryPromptIsFirstPersonAndEvidenceBounded() {
        val profile = AgentProfileEntity(
            id = "agent",
            workspaceId = "workspace",
            name = "分析员",
            avatarUri = null,
            identityMarkdown = "负责分析",
            soulMarkdown = "严谨",
            userMarkdown = null,
            systemPrompt = null,
            createdAt = 1L,
            updatedAt = 1L
        )
        val prompt = AgentDiaryPromptBuilder.build(
            profile,
            LocalDate.of(2026, 8, 19),
            "用户要求检查分页。"
        )
        assertTrue(prompt.contains("第一人称日记"))
        assertTrue(prompt.contains("用户要求检查分页。"))
        assertTrue(prompt.contains("不要把推测写成事实"))
    }

    @Test
    fun groupHistorySourceKeepsAgentMembershipBoundary() {
        val source = AgentGroupHistoryImport("agent", "conversation", "SUMMARY", "result-id")
        assertEquals("agent", source.agentId)
        assertEquals("SUMMARY", source.sourceType)
    }

    @Test
    fun groupPoliciesExposeThreeTurnModes() {
        assertEquals("MANAGER_SELECTS", AgentGroupPolicy.MANAGER_SELECTS)
        assertEquals("FREE_SPEAKING", AgentGroupPolicy.FREE_SPEAKING)
        assertEquals("USER_DIRECTED", AgentGroupPolicy.USER_DIRECTED)
    }
}
