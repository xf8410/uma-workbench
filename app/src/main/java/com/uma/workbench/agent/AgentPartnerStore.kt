package com.uma.workbench.agent

import java.time.LocalDate
import java.util.UUID

class AgentPartnerStore(private val database: AgentPartnerDatabase) {
    fun observeProfiles(workspaceId: String?) = database.profiles().observeForWorkspace(workspaceId)
    fun observeGroups(workspaceId: String?) = database.groups().observeForWorkspace(workspaceId)
    fun observeGroup(groupId: String) = database.groups().observeById(groupId)
    fun observeDiaries(agentId: String) = database.diaries().observe(agentId)
    fun observeGroupMessages(groupId: String) = database.groups().observeMessages(groupId)
    suspend fun groupMembers(groupId: String) = database.groups().members(groupId)
    suspend fun saveProfile(profile: AgentProfileEntity) { require(profile.name.isNotBlank()) { "伙伴名称不能为空" }; database.profiles().upsert(profile) }
    suspend fun saveDiary(agentId: String, date: LocalDate, title: String, content: String, sourceConversationId: String?, sourceMessageRange: String?, status: String = "DRAFT"): AgentDiaryEntryEntity {
        require(title.isNotBlank()) { "日记标题不能为空" }; require(content.isNotBlank()) { "日记正文不能为空" }; require(status in setOf("DRAFT", "PUBLISHED", "ARCHIVED")) { "未知的日记状态：$status" }
        val now = System.currentTimeMillis(); val existing = database.diaries().findForDate(agentId, date.toString())
        val entry = AgentDiaryEntryEntity(existing?.id ?: UUID.randomUUID().toString(), agentId, date.toString(), title, content, sourceConversationId, sourceMessageRange, status, existing?.createdAt ?: now, now)
        database.diaries().upsert(entry); return entry
    }
    suspend fun appendGroupMessage(groupId: String, senderType: String, senderAgentId: String?, content: String, replyToMessageId: String? = null, toolCallsJson: String? = null): AgentGroupMessageEntity {
        require(content.isNotBlank()) { "群消息不能为空" }; require(senderType in setOf("USER", "AGENT", "SYSTEM")) { "未知的群消息发送者类型：$senderType" }
        val message = AgentGroupMessageEntity(UUID.randomUUID().toString(), groupId, database.groups().nextMessageSequence(groupId), senderType, senderAgentId, content, replyToMessageId, toolCallsJson, System.currentTimeMillis())
        database.groups().insertMessage(message); return message
    }
    suspend fun recentGroupMessages(groupId: String, limit: Int = 20): List<AgentGroupMessageEntity> =
        database.groups().getRecentMessages(groupId, limit)

    suspend fun updateMessageRunning(messageId: String, requestId: String, model: String) {
        database.groups().updateMessageRunning(messageId, "RUNNING", requestId, model, System.currentTimeMillis())
    }

    suspend fun updateMessageCompleted(messageId: String, content: String, roundsCount: Int, usageJson: String?, toolCallsJson: String? = null) {
        database.groups().updateMessageContent(messageId, content)
        database.groups().updateMessageResult(messageId, "COMPLETED", System.currentTimeMillis(), null, roundsCount, usageJson)
        if (toolCallsJson != null) {
            database.groups().updateMessageToolCalls(messageId, toolCallsJson)
        }
    }

    suspend fun updateMessageFailed(messageId: String, error: String) {
        database.groups().updateMessageResult(messageId, "FAILED", System.currentTimeMillis(), error, 0, null)
    }

    suspend fun updateMessageCancelled(messageId: String) {
        database.groups().updateMessageStatus(messageId, "CANCELLED")
    }

    suspend fun updateMessageStatus(messageId: String, status: String) {
        database.groups().updateMessageStatus(messageId, status)
    }

    suspend fun updateGroup(group: AgentGroupEntity) {
        database.groups().upsert(group.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteGroup(groupId: String) {
        database.groups().clearMembers(groupId)
        database.groups().delete(groupId)
    }

    suspend fun addGroupMember(groupId: String, agentId: String, role: String = "MEMBER") {
        require(database.profiles().get(agentId) != null) { "找不到伙伴：$agentId" }
        val existing = database.groups().members(groupId)
        require(existing.none { it.agentId == agentId }) { "该伙伴已是群成员" }
        database.groups().upsertMembers(listOf(AgentGroupMemberEntity(groupId, agentId, role, "ON_DEMAND", System.currentTimeMillis())))
    }

    suspend fun removeGroupMember(groupId: String, agentId: String) {
        val group = database.groups().get(groupId) ?: error("群聊不存在")
        require(agentId != group.managerAgentId) { "不能移除群管理员，请先更换管理员" }
        database.groups().removeMember(groupId, agentId)
    }

    suspend fun changeGroupManager(groupId: String, newManagerId: String) {
        val group = database.groups().get(groupId) ?: error("群聊不存在")
        val members = database.groups().members(groupId)
        require(members.any { it.agentId == newManagerId }) { "新管理员必须是群成员" }
        database.groups().upsert(group.copy(managerAgentId = newManagerId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun createGroup(workspaceId: String?, name: String, description: String?, managerAgentId: String, memberAgentIds: List<String>, turnPolicy: String = AgentGroupPolicy.MANAGER_SELECTS, groupPrompt: String? = null, history: List<AgentGroupHistoryImport> = emptyList()): AgentGroupEntity {
        require(name.isNotBlank()) { "群名称不能为空" }; AgentGroupPolicy.validate(managerAgentId, memberAgentIds, turnPolicy)
        memberAgentIds.forEach { agentId -> require(database.profiles().get(agentId) != null) { "找不到群成员伙伴：$agentId" } }
        history.forEach { source -> require(source.agentId in memberAgentIds) { "历史来源伙伴不是群成员：${source.agentId}" } }
        val now = System.currentTimeMillis(); val group = AgentGroupEntity(UUID.randomUUID().toString(), workspaceId, name, description, managerAgentId, turnPolicy, groupPrompt, now, now)
        database.groups().upsert(group); database.groups().upsertMembers(memberAgentIds.map { AgentGroupMemberEntity(group.id, it, if (it == managerAgentId) "MANAGER" else "MEMBER", "ON_DEMAND", now) })
        if (history.isNotEmpty()) database.groups().upsertContextSources(history.map { AgentGroupContextSourceEntity(UUID.randomUUID().toString(), group.id, it.agentId, it.sourceConversationId, it.sourceType, it.sourceRef, now) })
        return group
    }
}
