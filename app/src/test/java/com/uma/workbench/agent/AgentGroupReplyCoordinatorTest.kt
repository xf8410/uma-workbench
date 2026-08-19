package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGroupReplyCoordinatorTest {
    @Test
    fun runsOnlySelectedMembersAndCapsReplies() = runBlocking {
        val seen = mutableListOf<String>()
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        val runner = AgentGroupReplyRunner { profile, prompt ->
            seen += "${profile.id}:$prompt"
            "答复-${profile.id}"
        }
        val replies = AgentGroupReplyCoordinator(runner, maxRepliesPerTurn = 2).execute(
            decision = AgentGroupTurnDecision("g", "a", listOf("a", "b", "c"), "bounded"),
            profiles = profiles,
            userMessage = "分析",
            groupContext = "[agent_group]\ngroupId=g"
        )
        assertEquals(listOf("a", "b"), replies.map { it.agentId })
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.contains("只读回答") })
    }

    private fun profile(id: String) = AgentProfileEntity(id, "w", id, null, "身份", "人格", null, null, true, 1L, 1L)
}
