package com.uma.workbench.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** Executes already-planned group turns without granting agents write or delegation tools. */
fun interface AgentGroupReplyRunner {
    suspend fun run(agent: AgentProfileEntity, prompt: String): AgentGroupReplyRunnerResult
}

data class AgentGroupReplyRunnerResult(
    val content: String,
    val requestId: String,
    val model: String?,
    val roundsCount: Int,
    val usageJson: String?,
    val toolCallsJson: String? = null
)

/** Callbacks for persisting per-agent message execution status. */
interface AgentGroupMessagePersister {
    suspend fun onRunning(messageId: String, requestId: String, model: String)
    suspend fun onCompleted(messageId: String, content: String, roundsCount: Int, usageJson: String?, toolCallsJson: String?)
    suspend fun onFailed(messageId: String, error: String)
    suspend fun onCancelled(messageId: String)
}

data class AgentGroupReply(
    val agentId: String,
    val content: String,
    val failed: Boolean = false
)

class AgentGroupReplyCoordinator(
    private val runner: AgentGroupReplyRunner,
    private val maxRepliesPerTurn: Int = 2
) {
    init { require(maxRepliesPerTurn in 1..8) }

    suspend fun execute(
        decision: AgentGroupTurnDecision,
        profiles: List<AgentProfileEntity>,
        userMessage: String,
        groupContext: String,
        historyMessages: List<AgentGroupMessageEntity> = emptyList(),
        persister: AgentGroupMessagePersister? = null,
        messageIds: Map<String, String> = emptyMap()
    ): List<AgentGroupReply> {
        require(userMessage.isNotBlank()) { "群消息不能为空" }
        val selected = decision.selectedAgentIds
            .distinct()
            .take(maxRepliesPerTurn)
            .mapNotNull { agentId -> profiles.firstOrNull { p -> p.id == agentId } }
        return supervisorScope {
            selected.map { profile ->
                async {
                    val messageId = messageIds[profile.id]
                    runCatching {
                        if (messageId != null) {
                            persister?.onRunning(messageId, "", "")
                        }
                        runner.run(
                            profile,
                            buildPrompt(profile, decision, userMessage, groupContext, historyMessages)
                        )
                    }.fold(
                        onSuccess = { result ->
                            messageId?.let { mid ->
                                persister?.onCompleted(mid, result.content, result.roundsCount, result.usageJson, result.toolCallsJson)
                            }
                            AgentGroupReply(profile.id, result.content)
                        },
                        onFailure = { e ->
                            val errorMsg = e.message ?: "未知错误"
                            messageId?.let { mid ->
                                persister?.onFailed(mid, errorMsg)
                            }
                            AgentGroupReply(profile.id, "Agent 执行失败：$errorMsg", true)
                        }
                    )
                }
            }.awaitAll()
        }
    }

    private fun buildPrompt(
        profile: AgentProfileEntity,
        decision: AgentGroupTurnDecision,
        userMessage: String,
        groupContext: String,
        historyMessages: List<AgentGroupMessageEntity> = emptyList()
    ): String = buildString {
        appendLine("你是群聊伙伴：${profile.name}。")
        appendLine("只读回答，不执行写入、发布、删除或凭据操作。")
        appendLine("结论必须基于实际证据；无法验证时明确说明。")
        appendLine("本轮管理员选择理由：${decision.reason}")
        appendLine(groupContext)
        if (historyMessages.isNotEmpty()) {
            appendLine("[group_history]")
            historyMessages.takeLast(10).forEach { msg ->
                val sender = when (msg.senderType) {
                    "USER" -> "用户"
                    "AGENT" -> msg.senderAgentId ?: "Agent"
                    else -> "系统"
                }
                appendLine("$sender: ${msg.content.take(500)}")
            }
        }
        appendLine("[user_message]")
        appendLine(userMessage)
    }
}
