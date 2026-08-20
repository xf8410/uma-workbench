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

        // Create PENDING placeholder messages for each selected agent
        val messageIds = if (pendingMessageIds.isNotEmpty()) {
            pendingMessageIds
        } else if (persister != null) {
            val ids = mutableMapOf<String, String>()
            decision.selectedAgentIds.distinct().forEach { agentId ->
                val placeholder = writer.append(
                    group.id, "AGENT", agentId, "\u23f3 \u6b63\u5728\u51c6\u5907\u56de\u590d...", null
                )
                ids[agentId] = placeholder.id
            }
            ids
        } else {
            emptyMap()
        }

        // Mark all pending as RUNNING
        if (persister != null) {
            messageIds.forEach { (_, messageId) ->
                persister.onRunning(messageId, "", "")
            }
        }

        val context = AgentGroupPolicy.buildContextInstructions(group, profiles, importedHistory)

        val replies = try {
            coordinator.execute(
                decision, profiles, userMessage, context, recentMessages,
                persister = persister, messageIds = messageIds
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            if (persister != null) {
                messageIds.forEach { (_, messageId) ->
                    persister.onCancelled(messageId)
                }
            }
            throw ce
        }

        // When persister is provided, Coordinator already called onCompleted/onFailed
        // with full metadata (content, roundsCount, usageJson, toolCallsJson)
        // Fallback: when no persister, write replies directly via writer
        if (persister == null) {
            replies.filter { !it.failed }.forEach { reply ->
                writer.append(
                    groupId = group.id,
                    senderType = "AGENT",
                    senderAgentId = reply.agentId,
                    content = reply.content,
                    toolCallsJson = null
                )
            }
        }

        return replies.filter { !it.failed }
    }
}
