package com.uma.workbench.agent

import java.time.LocalDate

object AgentDiaryPromptBuilder {
    fun build(agent: AgentProfileEntity, date: LocalDate, conversationText: String): String = """
        你是伙伴“${agent.name}”的日记整理器。
        请只根据下面实际发生的对话，写一篇第一人称日记。
        日期：$date
        要求：区分事实、决定、疑问和下一步；不要补写对话中没有出现的经历；不要把推测写成事实。
        输出适合长期保存的 Markdown 日记正文，不要输出分析过程。

        [conversation]
        $conversationText
    """.trimIndent()
}

data class AgentGroupHistoryImport(
    val agentId: String,
    val sourceConversationId: String?,
    val sourceType: String,
    val sourceRef: String
)

object AgentGroupPolicy {
    const val MANAGER_SELECTS = "MANAGER_SELECTS"
    const val FREE_SPEAKING = "FREE_SPEAKING"
    const val USER_DIRECTED = "USER_DIRECTED"

    fun validate(managerAgentId: String, memberAgentIds: List<String>, turnPolicy: String) {
        require(managerAgentId.isNotBlank()) { "群管理员伙伴不能为空" }
        require(managerAgentId in memberAgentIds) { "群管理员必须是群成员" }
        require(memberAgentIds.distinct().size == memberAgentIds.size) { "群成员不能重复" }
        require(memberAgentIds.isNotEmpty()) { "群至少需要一个伙伴成员" }
        require(turnPolicy in setOf(MANAGER_SELECTS, FREE_SPEAKING, USER_DIRECTED)) {
            "未知的群聊发言策略：$turnPolicy"
        }
    }

    fun buildContextInstructions(
        group: AgentGroupEntity,
        members: List<AgentProfileEntity>,
        importedHistory: List<AgentGroupHistoryImport>
    ): String = buildString {
        appendLine("[agent_group]")
        appendLine("groupId=${group.id}")
        appendLine("groupName=${group.name}")
        appendLine("turnPolicy=${group.turnPolicy}")
        appendLine("managerAgentId=${group.managerAgentId}")
        group.groupPrompt?.takeIf { it.isNotBlank() }?.let {
            appendLine("groupPrompt=")
            appendLine(it)
        }
        appendLine("[members]")
        members.forEach { member ->
            appendLine("agentId=${member.id}; name=${member.name}")
            appendLine(member.identityMarkdown)
        }
        if (importedHistory.isNotEmpty()) {
            appendLine("[imported_history_sources]")
            importedHistory.forEach { source ->
                appendLine("agentId=${source.agentId}; type=${source.sourceType}; ref=${source.sourceRef}")
            }
            appendLine("历史来源只代表可引用的上下文，不代表当前轮次已经读取了完整原文。")
        }
    }.trimEnd()
}
