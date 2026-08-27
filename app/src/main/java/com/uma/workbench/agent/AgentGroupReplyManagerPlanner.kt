package com.uma.workbench.agent

/**
 * Manager Agent planner: when turnPolicy is MANAGER_SELECTS and no explicit agent is requested,
 * the manager agent analyzes the user message and decides which members should respond.
 */
object AgentGroupReplyManagerPlanner {

    private const val MAX_RETRIES = 1

    suspend fun plan(
        runner: AgentGroupReplyRunner,
        managerProfile: AgentProfileEntity,
        members: List<AgentGroupMemberEntity>,
        memberProfiles: List<AgentProfileEntity>,
        userMessage: String,
        groupContext: String,
        maxReplies: Int = 2
    ): AgentGroupTurnDecision {
        require(members.isNotEmpty()) { "群成员不能为空" }
        val nonManagerMembers = members.filter { it.agentId != managerProfile.id }
        if (nonManagerMembers.isEmpty()) {
            return AgentGroupTurnDecision(
                groupId = "",
                managerAgentId = managerProfile.id,
                selectedAgentIds = emptyList(),
                reason = "群内除管理员外无其他成员"
            )
        }

        val prompt = buildManagerPrompt(managerProfile, nonManagerMembers, memberProfiles, userMessage, groupContext, maxReplies)

        var lastError: String? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                val result = runner.run(managerProfile, prompt)
                val parsed = parseDecision(result.content, nonManagerMembers.map { it.agentId }, maxReplies)
                if (parsed != null && parsed.first.isNotEmpty()) {
                    return AgentGroupTurnDecision(
                        groupId = "",
                        managerAgentId = managerProfile.id,
                        selectedAgentIds = parsed.first,
                        reason = parsed.second.ifBlank { "管理员分析后选择" }
                    )
                }
                lastError = "管理员返回内容无法解析为有效决策"
            } catch (e: Exception) {
                lastError = e.message ?: "管理员执行异常"
            }
        }

        // Fallback: pick first non-manager member
        val fallbackId = nonManagerMembers.first().agentId
        return AgentGroupTurnDecision(
            groupId = "",
            managerAgentId = managerProfile.id,
            selectedAgentIds = listOf(fallbackId),
            reason = "管理员决策失败（$lastError），回退选择首个成员"
        )
    }

    private fun buildManagerPrompt(
        manager: AgentProfileEntity,
        nonManagerMembers: List<AgentGroupMemberEntity>,
        memberProfiles: List<AgentProfileEntity>,
        userMessage: String,
        groupContext: String,
        maxReplies: Int
    ): String = buildString {
        appendLine("你是群聊管理员「${manager.name}」。")
        appendLine("你的职责是分析用户消息，从群成员中选择最合适的伙伴来回答。")
        appendLine()
        appendLine("## 可用群成员")
        nonManagerMembers.forEach { member ->
            val profile = memberProfiles.firstOrNull { it.id == member.agentId }
            val identity = profile?.identityMarkdown?.take(200) ?: "无描述"
            appendLine("- ${profile?.name ?: member.agentId}（ID: ${member.agentId}）：$identity")
        }
        appendLine()
        if (groupContext.isNotBlank()) {
            appendLine("## 群聊背景")
            appendLine(groupContext.take(500))
            appendLine()
        }
        appendLine("## 用户消息")
        appendLine(userMessage)
        appendLine()
        appendLine("## 输出要求")
        appendLine("请分析用户消息需要哪些成员的专业能力，然后输出严格 JSON：")
        appendLine("```json")
        appendLine("{")
        appendLine("  \"selectedAgentIds\": [\"id1\", \"id2\"],")
        appendLine("  \"reason\": \"选择理由\"")
        appendLine("}")
        appendLine("```")
        appendLine("要求：")
        appendLine("1. selectedAgentIds 只能包含上述成员列表中的 ID")
        appendLine("2. 最多选择 $maxReplies 个成员")
        appendLine("3. 只输出 JSON，不要输出其他内容")
    }

    /**
     * Parse the manager's response to extract selectedAgentIds and reason.
     * Returns null if parsing fails completely.
     */
    internal fun parseDecision(
        response: String,
        validAgentIds: List<String>,
        maxReplies: Int
    ): Pair<List<String>, String>? {
        // Try to extract JSON block from response
        val jsonMatch = Regex("\\{[\\s\\S]*?\"selectedAgentIds\"[\\s\\S]*?\\}").find(response)
        if (jsonMatch != null) {
            return tryParseJson(jsonMatch.value, validAgentIds, maxReplies)
        }

        // Fallback: try to find agent IDs mentioned in the response
        val foundIds = validAgentIds.filter { id -> response.contains(id) }
        if (foundIds.isNotEmpty()) {
            return Pair(foundIds.take(maxReplies), "从管理员回复中提取到成员 ID")
        }

        return null
    }

    private fun tryParseJson(
        jsonStr: String,
        validAgentIds: List<String>,
        maxReplies: Int
    ): Pair<List<String>, String>? {
        return try {
            // Simple JSON parsing without kotlinx.serialization dependency
            val idsMatch = Regex("\"selectedAgentIds\"\\s*:\\s*\\[([^\\]]*)]").find(jsonStr)
            val reasonMatch = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"").find(jsonStr)

            val rawIds = idsMatch?.groupValues?.get(1) ?: return null
            val ids = Regex("\"([^\"]*)\"").findAll(rawIds)
                .map { it.groupValues[1] }
                .filter { it in validAgentIds }
                .distinct()
                .take(maxReplies)
                .toList()

            val reason = reasonMatch?.groupValues?.get(1) ?: ""

            if (ids.isEmpty()) null else Pair(ids, reason)
        } catch (e: Exception) {
            null
        }
    }
}
