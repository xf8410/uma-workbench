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
        importedHistory: List<AgentGroupHistoryImport> = emptyList(),
        recentMessages: List<AgentGroupMessageEntity> = emptyList(),
        persister: AgentGroupMessagePersister? = null,
        pendingMessageIds: Map<String, String> = emptyMap()
    ): List<AgentGroupReply> {
        val decision = AgentGroupTurnPlanner.plan(group, members, userMessage, requestedAgentIds)
        if (decision.selectedAgentIds.isEmpty()) return emptyList()

        // Create PENDING placeholder messages for each selected agent (if not already created)
        val messageIds = if (pendingMessageIds.isNotEmpty()) {
            pendingMessageIds
        } else if (persister != null) {
            val ids = mutableMapOf<String, String>()
            decision.selectedAgentIds.distinct().forEach { agentId ->
                val profile = profiles.firstOrNull { p -> p.id == agentId }
                val name = profile?.name ?: agentId
                val placeholder = writer.append(
                    group.id, "AGENT", agentId, "⏳ 正在准备回复...", null
                )
                ids[agentId] = placeholder.id
            }
            ids
        } else {
            emptyMap()
        }

        val context = AgentGroupPolicy.buildContextInstructions(group, profiles, importedHistory)

        val replies = try {
            coordinator.execute(
                decision, profiles, userMessage, context, recentMessages,
                persister = persister, messageIds = messageIds
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            messageIds.forEach { (_, messageId) ->
                persister?.onCancelled(messageId)
            }
            throw ce
        }

        return replies.filter { !it.failed }
    }
}
