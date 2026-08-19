package com.uma.workbench.agent

import java.time.LocalDate
import java.util.UUID

class AgentPartnerStore(private val database: AgentPartnerDatabase) {
    fun observeProfiles(workspaceId: String?) = database.profiles().observeForWorkspace(workspaceId)

    fun observeGroups(workspaceId: String?) = database.groups().observeForWorkspace(workspaceId)

    fun observeDiaries(agentId: String) = database.diaries().observe(agentId)

    suspend fun saveProfile(profile: AgentProfileEntity) {
        require(profile.name.isNotBlank()) { "伙伴名称不能为空" }
        database.profiles().upsert(profile)
    }

    suspend fun saveDiary(
        agentId: String,
        date: LocalDate,
        title: String,
        content: String,
        sourceConversationId: String?,
        sourceMessageRange: String?,
        status: String = "DRAFT"
    ): AgentDiaryEntryEntity {
        require(title.isNotBlank()) { "日记标题不能为空" }
        require(content.isNotBlank()) { "日记正文不能为空" }
        require(status in setOf("DRAFT", "PUBLISHED", "ARCHIVED")) { "未知的日记状态：$status" }
        val now = System.currentTimeMillis()
        val existing = database.diaries().findForDate(agentId, date.toString())
        val entry = AgentDiaryEntryEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            agentId = agentId,
            dateKey = date.toString(),
            title = title,
            content = content,
            sourceConversationId = sourceConversationId,
            sourceMessageRange = sourceMessageRange,
            status = status,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        database.diaries().upsert(entry)
        return entry
    }

    suspend fun createGroup(
        workspaceId: String?,
        name: String,
        description: String?,
        managerAgentId: String,
        memberAgentIds: List<String>,
        turnPolicy: String = AgentGroupPolicy.MANAGER_SELECTS,
        groupPrompt: String? = null,
        history: List<AgentGroupHistoryImport> = emptyList()
    ): AgentGroupEntity {
        require(name.isNotBlank()) { "群名称不能为空" }
        AgentGroupPolicy.validate(managerAgentId, memberAgentIds, turnPolicy)
        memberAgentIds.forEach { agentId ->
            require(database.profiles().get(agentId) != null) { "找不到群成员伙伴：$agentId" }
        }
        history.forEach { source ->
            require(source.agentId in memberAgentIds) { "历史来源伙伴不是群成员：${source.agentId}" }
        }
        val now = System.currentTimeMillis()
        val group = AgentGroupEntity(
            id = UUID.randomUUID().toString(),
            workspaceId = workspaceId,
            name = name,
            description = description,
            managerAgentId = managerAgentId,
            turnPolicy = turnPolicy,
            groupPrompt = groupPrompt,
            createdAt = now,
            updatedAt = now
        )
        database.groups().upsert(group)
        database.groups().upsertMembers(
            memberAgentIds.map { agentId ->
                AgentGroupMemberEntity(
                    groupId = group.id,
                    agentId = agentId,
                    role = if (agentId == managerAgentId) "MANAGER" else "MEMBER",
                    joinedAt = now
                )
            }
        )
        if (history.isNotEmpty()) {
            database.groups().upsertContextSources(
                history.map { source ->
                    AgentGroupContextSourceEntity(
                        id = UUID.randomUUID().toString(),
                        groupId = group.id,
                        agentId = source.agentId,
                        sourceConversationId = source.sourceConversationId,
                        sourceType = source.sourceType,
                        sourceRef = source.sourceRef,
                        importedAt = now
                    )
                }
            )
        }
        return group
    }
}
