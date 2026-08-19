package com.uma.workbench.agent

fun interface AgentGroupMessageWriter {
    suspend fun append(
        groupId: String,
        senderType: String,
        senderAgentId: String?,
        content: String,
        toolCallsJson: String?
    ): AgentGroupMessageEntity
}

/** Application service for the group reply execution boundary. */
class AgentGroupReplyService(
    private val writer: AgentGroupMessageWriter,
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
            writer.append(
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
