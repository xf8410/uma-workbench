package com.uma.workbench.agent

/** A bounded, provider-independent decision made before an Agent is invoked. */
data class AgentGroupTurnDecision(
    val groupId: String,
    val managerAgentId: String,
    val selectedAgentIds: List<String>,
    val reason: String
)

object AgentGroupTurnPlanner {
    fun plan(
        group: AgentGroupEntity,
        members: List<AgentGroupMemberEntity>,
        userMessage: String,
        requestedAgentIds: List<String> = emptyList()
    ): AgentGroupTurnDecision {
        require(userMessage.isNotBlank()) { "群消息不能为空" }
        require(members.any { it.agentId == group.managerAgentId }) { "群管理员不是群成员" }
        val memberIds = members.map { it.agentId }.distinct()
        val requested = requestedAgentIds.filter { it in memberIds }.distinct()
        val selected = when (group.turnPolicy) {
            AgentGroupPolicy.USER_DIRECTED -> requested
            AgentGroupPolicy.MANAGER_SELECTS -> requested.ifEmpty {
                members.filter { it.agentId != group.managerAgentId }
                    .sortedBy { it.joinedAt }
                    .take(1)
                    .map { it.agentId }
            }
            AgentGroupPolicy.FREE_SPEAKING -> members
                .filter { it.agentId != group.managerAgentId }
                .sortedBy { it.joinedAt }
                .take(2)
                .map { it.agentId }
            else -> error("未知的群聊发言策略：${group.turnPolicy}")
        }
        return AgentGroupTurnDecision(
            groupId = group.id,
            managerAgentId = group.managerAgentId,
            selectedAgentIds = selected,
            reason = when (group.turnPolicy) {
                AgentGroupPolicy.USER_DIRECTED -> "按用户指定的群成员选择"
                AgentGroupPolicy.MANAGER_SELECTS -> if (requested.isEmpty()) "管理员策略选择首个可发言成员" else "采用用户指定成员，未绕过管理员策略"
                AgentGroupPolicy.FREE_SPEAKING -> "自由发言模式最多选择两个成员"
                else -> ""
            }
        )
    }
}
