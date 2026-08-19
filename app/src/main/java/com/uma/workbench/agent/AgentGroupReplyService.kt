package com.uma.workbench.agent

/** Android-independent application service for the group reply execution boundary. */
class AgentGroupReplyService(
    private val store: AgentPartnerStore,
    private val coordinator: AgentGroupReplyCoordinator
) {
    suspend fun executeAndPersist(
        group: AgentGroupEntity,
        members: List<AgentGroupMemberEntity>,
        profiles: List<AgentProfileEntity>,
        userMessage: String,
        requestedAgentIds: List<String> = emptyList(),
        importedHistory: List<AgentGroupHistoryImport> = emptyList()
    ): List<AgentGroupReply> {
        val decision = AgentGroupTurnPlanner.plan(group, members, userMessage, requestedAgentIds)
        if (decision.selectedAgentIds.isEmpty()) return emptyList()
        val context = AgentGroupPolicy.buildContextInstructions(group, profiles, importedHistory)
        val replies = coordinator.execute(decision, profiles, userMessage, context)
        replies.forEach { reply ->
            store.appendGroupMessage(
                groupId = group.id,
                senderType = "AGENT",
                senderAgentId = reply.agentId,
                content = reply.content,
                toolCallsJson = if (reply.failed) "{\"failed\":true}" else null
            )
        }
        return replies
    }
}
