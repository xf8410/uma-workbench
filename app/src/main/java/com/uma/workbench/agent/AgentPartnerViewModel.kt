package com.uma.workbench.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed class AgentGenerationState {
    data object Idle : AgentGenerationState()
    data class Generating(val agentNames: Map<String, String>) : AgentGenerationState()
}

class AgentPartnerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as com.uma.workbench.WorkbenchApplication
    private val store = app.agentPartnerStore
    private val catalogStore = AiProviderCatalogStore(application)
    private val provider = CatalogAiStreamingProvider {
        val cat = catalogStore.load()
        val mid = cat.defaultModel?.modelId
        if (mid != null) cat.providers.firstOrNull { p -> mid in p.models } else null
    }
    private val _generationState = MutableStateFlow<AgentGenerationState>(AgentGenerationState.Idle)
    val generationState: StateFlow<AgentGenerationState> = _generationState.asStateFlow()
    private var generationJob: Job? = null
    private val workspaceId = MutableStateFlow<String?>(null)
    private val selectedGroupId = MutableStateFlow<String?>(null)
    val profiles: StateFlow<List<AgentProfileEntity>> = workspaceId.flatMapLatest { store.observeProfiles(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val groups: StateFlow<List<AgentGroupEntity>> = workspaceId.flatMapLatest { store.observeGroups(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedGroup: StateFlow<AgentGroupEntity?> = selectedGroupId.flatMapLatest { id -> if (id == null) flowOf(null) else store.observeGroup(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val groupMessages: StateFlow<List<AgentGroupMessageEntity>> = selectedGroupId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else store.observeGroupMessages(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun setWorkspace(id: String) { if (workspaceId.value != id) { workspaceId.value = id; selectedGroupId.value = null } }
    fun selectGroup(id: String?) { selectedGroupId.value = id }
    fun clearMessage() { _message.value = null }
    fun createProfile(name: String, identity: String, soul: String, user: String?) = viewModelScope.launch {
        runCatching {
            val now = System.currentTimeMillis()
            store.saveProfile(AgentProfileEntity(UUID.randomUUID().toString(), workspaceId.value, name.trim(), null, identity, soul, user, null, true, now, now))
        }.onSuccess { _message.value = "伙伴已创建" }.onFailure { _message.value = it.message ?: "创建伙伴失败" }
    }
    fun createGroup(name: String, managerId: String, memberIds: List<String>, policy: String) = viewModelScope.launch {
        runCatching { store.createGroup(workspaceId.value, name.trim(), null, managerId, memberIds, policy) }
            .onSuccess { _message.value = "群聊已创建" }.onFailure { _message.value = it.message ?: "创建群聊失败" }
    }

    fun sendGroupMessage(content: String, requestedAgentIds: List<String> = emptyList()) = viewModelScope.launch {
        val groupId = selectedGroupId.value ?: run { _message.value = "请先选择群聊"; return@launch }
        val group = groups.value.firstOrNull { it.id == groupId } ?: run { _message.value = "找不到当前群聊"; return@launch }
        generationJob?.cancel()
        generationJob = launch {
            runCatching {
                val members = store.groupMembers(groupId)
                val allProfiles = this@AgentPartnerViewModel.profiles.value.filter { it.id in members.map { m -> m.agentId } }
                val recentMessages = store.recentGroupMessages(groupId, 10)
                store.appendGroupMessage(groupId, "USER", null, content.trim())
                // Create runner early (needed for both manager planning and agent replies)
                val runner = AgentGroupReplyRunnerImpl(
                    provider = provider,
                    source = AndroidReadonlyAgentToolDataSource(
                        app, app.database, workspaceId.value ?: "",
                        { ActiveWorkspaceDocumentBridge.document.value?.takeIf { it.workspaceId == (workspaceId.value ?: "") } }
                    ),
                    filesDir = app.filesDir
                )
                // Determine decision: use Manager Agent for MANAGER_SELECTS, otherwise rule-based
                val decision = if (group.turnPolicy == AgentGroupPolicy.MANAGER_SELECTS && requestedAgentIds.isEmpty()) {
                    val managerProfile = allProfiles.firstOrNull { it.id == group.managerAgentId }
                    if (managerProfile != null) {
                        AgentGroupReplyManagerPlanner.plan(
                            runner = runner,
                            managerProfile = managerProfile,
                            members = members,
                            memberProfiles = allProfiles,
                            userMessage = content,
                            groupContext = AgentGroupPolicy.buildContextInstructions(group, allProfiles, emptyList()),
                            maxReplies = 2
                        ).copy(groupId = groupId)
                    } else {
                        AgentGroupTurnPlanner.plan(group, members, content, requestedAgentIds)
                    }
                } else {
                    AgentGroupTurnPlanner.plan(group, members, content, requestedAgentIds)
                }
                if (decision.selectedAgentIds.isEmpty()) {
                    _message.value = "本轮无伙伴被选中发言"
                    return@runCatching
                }
                store.appendGroupMessage(groupId, "SYSTEM", null, "管理员已选择：${decision.selectedAgentIds.joinToString()}（${decision.reason}）")
                val selectedProfiles = allProfiles.filter { it.id in decision.selectedAgentIds }
                val agentNames = selectedProfiles.associate { it.id to it.name }
                _generationState.value = AgentGenerationState.Generating(agentNames)
                // Create PENDING placeholder messages for each selected agent
                val pendingIds = mutableMapOf<String, String>()
                selectedProfiles.forEach { profile ->
                    val placeholder = store.appendGroupMessage(groupId, "AGENT", profile.id, "⏳ 等待回复...")
                    pendingIds[profile.id] = placeholder.id
                }
                val coordinator = AgentGroupReplyCoordinator(runner)
                val writer = object : AgentGroupMessageWriter {
                    override suspend fun append(groupId: String, senderType: String, senderAgentId: String?, content: String, toolCallsJson: String?): AgentGroupMessageEntity {
                        return store.appendGroupMessage(groupId, senderType, senderAgentId, content, toolCallsJson = toolCallsJson)
                    }
                }
                val service = AgentGroupReplyService(writer, coordinator)
                val persister = object : AgentGroupMessagePersister {
                    override suspend fun onRunning(messageId: String, requestId: String, model: String) {
                        store.updateMessageRunning(messageId, requestId, model)
                    }
                    override suspend fun onCompleted(messageId: String, content: String, roundsCount: Int, usageJson: String?, toolCallsJson: String?) {
                        store.updateMessageCompleted(messageId, content, roundsCount, usageJson, toolCallsJson)
                    }
                    override suspend fun onFailed(messageId: String, error: String) {
                        store.updateMessageFailed(messageId, error)
                    }
                    override suspend fun onCancelled(messageId: String) {
                        store.updateMessageCancelled(messageId)
                    }
                }
                try {
                    val replies = service.executeAndPersist(
                        group = group,
                        members = members,
                        profiles = selectedProfiles,
                        userMessage = content,
                        requestedAgentIds = requestedAgentIds,
                        recentMessages = recentMessages,
                        persister = persister,
                        pendingMessageIds = pendingIds
                    )
                    _generationState.value = AgentGenerationState.Idle
                    _message.value = null
                } catch (ce: CancellationException) {
                    _generationState.value = AgentGenerationState.Idle
                    throw ce
                } catch (e: Throwable) {
                    _generationState.value = AgentGenerationState.Idle
                    _message.value = "Agent 回复失败：${e.message ?: "未知错误"}"
                }
            }.onFailure {
                _generationState.value = AgentGenerationState.Idle
                _message.value = it.message ?: "发送群消息失败"
            }
        }
    }

    fun cancelGroupGeneration() {
        generationJob?.cancel()
        generationJob = null
        _generationState.value = AgentGenerationState.Idle
    }

    fun updateGroupSettings(
        groupId: String,
        name: String,
        description: String?,
        groupPrompt: String?,
        managerAgentId: String,
        turnPolicy: String
    ) = viewModelScope.launch {
        runCatching {
            val current = store.groupMembers(groupId)
            AgentGroupPolicy.validate(managerAgentId, current.map { it.agentId }, turnPolicy)
            val group = groups.value.firstOrNull { it.id == groupId } ?: error("群聊不存在")
            store.updateGroup(group.copy(
                name = name.trim(),
                description = description?.takeIf { it.isNotBlank() },
                groupPrompt = groupPrompt?.takeIf { it.isNotBlank() },
                managerAgentId = managerAgentId,
                turnPolicy = turnPolicy
            ))
        }.onSuccess { _message.value = "群设置已更新" }
         .onFailure { _message.value = it.message ?: "更新群设置失败" }
    }

    fun addGroupMember(agentId: String) = viewModelScope.launch {
        val groupId = selectedGroupId.value ?: return@launch
        runCatching { store.addGroupMember(groupId, agentId) }
            .onSuccess { _message.value = "成员已添加" }
            .onFailure { _message.value = it.message ?: "添加成员失败" }
    }

    fun removeGroupMember(agentId: String) = viewModelScope.launch {
        val groupId = selectedGroupId.value ?: return@launch
        runCatching { store.removeGroupMember(groupId, agentId) }
            .onSuccess { _message.value = "成员已移除" }
            .onFailure { _message.value = it.message ?: "移除成员失败" }
    }

    fun deleteCurrentGroup() = viewModelScope.launch {
        val groupId = selectedGroupId.value ?: return@launch
        runCatching { store.deleteGroup(groupId) }
            .onSuccess { selectedGroupId.value = null; _message.value = "群聊已删除" }
            .onFailure { _message.value = it.message ?: "删除群聊失败" }
    }


    fun generateDiaryNow(agentId: String? = null) = viewModelScope.launch {
        runCatching {
            app.workScheduler.scheduleDiary(agentId, intervalHours = 24)
        }.onSuccess { _message.value = "日记生成任务已调度" }
         .onFailure { _message.value = it.message ?: "调度日记任务失败" }
    }

    fun cancelDiarySchedule(agentId: String? = null) = viewModelScope.launch {
        runCatching {
            app.workScheduler.cancelDiary(agentId)
        }.onSuccess { _message.value = "日记定时任务已取消" }
         .onFailure { _message.value = it.message ?: "取消日记任务失败" }
    }

    fun triggerDiaryGeneration(agentId: String? = null) = viewModelScope.launch {
        runCatching {
            val db = com.uma.workbench.agent.AgentPartnerDatabase.get(getApplication())
            val store = app.agentPartnerStore
            val catalogStore = AiProviderCatalogStore(getApplication())
            val catalog = catalogStore.load()
            val aiProfile = catalog.defaultModel?.let { mid ->
                catalog.providers.firstOrNull { p -> mid in p.models }
            } ?: error("未配置 AI 模型")
            val aiProvider = CatalogAiStreamingProvider { aiProfile }
            val targetIds = if (agentId != null) {
                listOf(agentId)
            } else {
                db.profiles().getAllEnabled().map { it.id }
            }
            var count = 0
            val today = java.time.LocalDate.now()
            targetIds.forEach { targetId ->
                val agentProfile = db.profiles().get(targetId) ?: return@forEach
                val memberEntries = db.groups().groupsContainingMember(targetId)
                if (memberEntries.isEmpty()) return@forEach
                val conversationText = buildString {
                    memberEntries.forEach { group ->
                        val messages = db.groups().getRecentMessages(group.id, 20)
                        if (messages.isNotEmpty()) {
                            appendLine("## 群聊：${group.name}")
                            messages.forEach { msg ->
                                val sender = when (msg.senderType) {
                                    "USER" -> "用户"
                                    "AGENT" -> if (msg.senderAgentId == targetId) agentProfile.name else (msg.senderAgentId ?: "Agent")
                                    else -> "系统"
                                }
                                appendLine("$sender: ${msg.content.take(500)}")
                            }
                            appendLine()
                        }
                    }
                }
                if (conversationText.isBlank()) return@forEach
                val prompt = AgentDiaryPromptBuilder.build(agentProfile, today, conversationText)
                val request = AiGenerationRequest(
                    requestId = java.util.UUID.randomUUID().toString(),
                    messages = listOf(AiPromptMessage(role = "user", completeContent = prompt)),
                    model = catalog.defaultModel,
                    tools = null
                )
                var fullText = ""
                aiProvider.stream(request).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta -> fullText += event.completeDelta
                        else -> {}
                    }
                }
                if (fullText.isNotBlank()) {
                    store.saveDiary(
                        agentId = targetId,
                        date = today,
                        title = "${agentProfile.name} 的日记 - $today",
                        content = fullText.trim(),
                        sourceConversationId = null,
                        sourceMessageRange = null,
                        status = "DRAFT"
                    )
                    count++
                }
            }
            _message.value = "已生成 $count 篇日记"
        }.onFailure { _message.value = it.message ?: "生成日记失败" }
    }

    fun retryFailedMessage(messageId: String) = viewModelScope.launch {
        val message = groupMessages.value.firstOrNull { it.id == messageId } ?: return@launch
        if (message.status != "FAILED") return@launch
        val agentId = message.senderAgentId ?: return@launch
        store.updateMessageStatus(messageId, "PENDING")
        generationJob?.cancel()
        generationJob = launch {
            runCatching {
                val groupId = message.groupId
                val group = groups.value.firstOrNull { it.id == groupId } ?: return@runCatching
                val members = store.groupMembers(groupId)
                val profile = profiles.value.firstOrNull { it.id == agentId } ?: return@runCatching
                val recentMessages = store.recentGroupMessages(groupId, 10)
                val agentNames = mapOf(agentId to profile.name)
                _generationState.value = AgentGenerationState.Generating(agentNames)
                store.updateMessageRunning(messageId, "", "")
                val runner = AgentGroupReplyRunnerImpl(
                    provider = provider,
                    source = AndroidReadonlyAgentToolDataSource(
                        app, app.database, workspaceId.value ?: "",
                        { ActiveWorkspaceDocumentBridge.document.value?.takeIf { it.workspaceId == (workspaceId.value ?: "") } }
                    ),
                    filesDir = app.filesDir
                )
                val prompt = buildRetryPrompt(profile, group, recentMessages, message.content)
                try {
                    val result = runner.run(profile, prompt)
                    store.updateMessageCompleted(messageId, result.content, result.roundsCount, result.usageJson)
                    _generationState.value = AgentGenerationState.Idle
                } catch (ce: CancellationException) {
                    _generationState.value = AgentGenerationState.Idle
                    throw ce
                } catch (e: Throwable) {
                    store.updateMessageFailed(messageId, e.message ?: "重试失败")
                    _generationState.value = AgentGenerationState.Idle
                }
            }.onFailure {
                _generationState.value = AgentGenerationState.Idle
            }
        }
    }

    private fun buildRetryPrompt(
        profile: AgentProfileEntity,
        group: AgentGroupEntity,
        recentMessages: List<AgentGroupMessageEntity>,
        originalContent: String
    ): String = buildString {
        appendLine("你是群聊伙伴：${profile.name}。")
        appendLine("只读回答，不执行写入、发布、删除或凭据操作。")
        appendLine("结论必须基于实际证据；无法验证时明确说明。")
        appendLine(AgentGroupPolicy.buildContextInstructions(group, listOf(profile), emptyList()))
        if (recentMessages.isNotEmpty()) {
            appendLine("[group_history]")
            recentMessages.takeLast(10).forEach { msg ->
                val sender = when (msg.senderType) {
                    "USER" -> "用户"
                    "AGENT" -> msg.senderAgentId ?: "Agent"
                    else -> "系统"
                }
                appendLine("$sender: ${msg.content.take(500)}")
            }
        }
        appendLine("[retry] 上一次回复失败，请重新回答以下用户消息：")
        appendLine("[user_message]")
        val lastUserMsg = recentMessages.lastOrNull { it.senderType == "USER" }
        appendLine(lastUserMsg?.content ?: "")
    }
}
