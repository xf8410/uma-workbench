package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGroupReplyServiceTest {
    @Test
    fun serviceUsesCoordinatorOutputForPersistence() = runBlocking {
        val messages = mutableListOf<AgentGroupMessageEntity>()
        val store = object : AgentPartnerStoreFixture() {
            override suspend fun appendGroupMessage(
                groupId: String,
                senderType: String,
                senderAgentId: String?,
                content: String,
                replyToMessageId: String?,
                toolCallsJson: String?
            ): AgentGroupMessageEntity {
                return AgentGroupMessageEntity("m${messages.size}", groupId, messages.size.toLong(), senderType, senderAgentId, content, replyToMessageId, toolCallsJson, 1L).also { messages += it }
            }
        }
        val service = AgentGroupReplyService(
            store,
            AgentGroupReplyCoordinator(AgentGroupReplyRunner { _, _ -> "有证据的答复" })
        )
        service.executeAndPersist(
            group(),
            members(),
            listOf(profile("manager"), profile("member")),
            "请分析"
        )
        assertEquals("AGENT", messages.single().senderType)
        assertEquals("member", messages.single().senderAgentId)
    }

    private fun group() = AgentGroupEntity("g", "w", "组", null, "manager", AgentGroupPolicy.MANAGER_SELECTS, null, 1L, 1L)
    private fun members() = listOf(AgentGroupMemberEntity("g", "manager", "MANAGER", "ON_DEMAND", 1L), AgentGroupMemberEntity("g", "member", "MEMBER", "ON_DEMAND", 2L))
    private fun profile(id: String) = AgentProfileEntity(id, "w", id, null, "身份", "人格", null, null, true, 1L, 1L)
}

abstract class AgentPartnerStoreFixture : AgentPartnerStoreContract {
    override suspend fun appendGroupMessage(groupId: String, senderType: String, senderAgentId: String?, content: String, replyToMessageId: String?, toolCallsJson: String?): AgentGroupMessageEntity = error("fixture")
}

interface AgentPartnerStoreContract {
    suspend fun appendGroupMessage(groupId: String, senderType: String, senderAgentId: String?, content: String, replyToMessageId: String? = null, toolCallsJson: String? = null): AgentGroupMessageEntity
}
