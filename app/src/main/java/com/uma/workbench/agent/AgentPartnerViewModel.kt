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
                val decision = AgentGroupTurnPlanner.plan(group, members, content, requestedAgentIds)
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
                val runner = AgentGroupReplyRunnerImpl(
                    provider = provider,
                    source = AndroidReadonlyAgentToolDataSource(
                        app, app.database, workspaceId.value ?: "",
                        { ActiveWorkspaceDocumentBridge.document.value?.takeIf { it.workspaceId == (workspaceId.value ?: "") } }
                    ),
                    filesDir = app.filesDir
                )
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
                    override suspend fun onCompleted(messageId: String, content: String, roundsCount: Int, usageJson: String?) {
                        store.updateMessageCompleted(messageId, content, roundsCount, usageJson)
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
