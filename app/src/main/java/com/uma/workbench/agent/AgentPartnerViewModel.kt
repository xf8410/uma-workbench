package com.uma.workbench.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

class AgentPartnerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as com.uma.workbench.WorkbenchApplication
    private val store = app.agentPartnerStore
    private val catalogStore = AiProviderCatalogStore(application)
    private val provider = CatalogAiStreamingProvider { val cat = catalogStore.load(); val mid = cat.defaultModel?.modelId; if (mid != null) cat.providers.firstOrNull { p -> mid in p.models } else null }
    private val _replyingAgents = MutableStateFlow<Map<String, String>>(emptyMap())
    val replyingAgents: StateFlow<Map<String, String>> = _replyingAgents.asStateFlow()
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
    fun createProfile(name: String, identity: String, soul: String, user: String?) = viewModelScope.launch { runCatching { val now = System.currentTimeMillis(); store.saveProfile(AgentProfileEntity(UUID.randomUUID().toString(), workspaceId.value, name.trim(), null, identity, soul, user, null, true, now, now)) }.onSuccess { _message.value = "伙伴已创建" }.onFailure { _message.value = it.message ?: "创建伙伴失败" } }
    fun createGroup(name: String, managerId: String, memberIds: List<String>, policy: String) = viewModelScope.launch { runCatching { store.createGroup(workspaceId.value, name.trim(), null, managerId, memberIds, policy) }.onSuccess { _message.value = "群聊已创建" }.onFailure { _message.value = it.message ?: "创建群聊失败" } }
    fun sendGroupMessage(content: String, requestedAgentIds: List<String> = emptyList()) = viewModelScope.launch {
        val groupId = selectedGroupId.value ?: run { _message.value = "请先选择群聊"; return@launch }
        val group = groups.value.firstOrNull { it.id == groupId } ?: run { _message.value = "找不到当前群聊"; return@launch }
        runCatching {
            val members = store.groupMembers(groupId)
            val profiles = this@AgentPartnerViewModel.profiles.value.filter { it.id in members.map { m -> m.agentId } }
            val recentMessages = store.recentGroupMessages(groupId, 10)
            store.appendGroupMessage(groupId, "USER", null, content.trim())
            val decision = AgentGroupTurnPlanner.plan(group, members, content, requestedAgentIds)
            if (decision.selectedAgentIds.isEmpty()) {
                _message.value = "本轮无伙伴被选中发言"
                return@runCatching
            }
            store.appendGroupMessage(groupId, "SYSTEM", null, "管理员已选择：${decision.selectedAgentIds.joinToString()}（${decision.reason}）")
            val selectedProfiles = profiles.filter { it.id in decision.selectedAgentIds }
            _replyingAgents.value = selectedProfiles.associate { it.id to "GENERATING" }
            launch {
                try {
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
                    val replies = service.executeAndPersist(
                        group = group,
                        members = members,
                        profiles = selectedProfiles,
                        userMessage = content,
                        requestedAgentIds = requestedAgentIds,
                        recentMessages = recentMessages
                    )
                    replies.forEach { reply ->
                        _replyingAgents.value = _replyingAgents.value - reply.agentId
                    }
                    _message.value = null
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    _replyingAgents.value = emptyMap()
                    throw ce
                } catch (e: Throwable) {
                    _replyingAgents.value = _replyingAgents.value.mapValues { "FAILED" }
                    _message.value = "Agent 回复失败：${e.message ?: "未知错误"}"
                }
            }
        }.onFailure { _message.value = it.message ?: "发送群消息失败" }
    }
}
