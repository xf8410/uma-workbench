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

class AgentPartnerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as com.uma.workbench.WorkbenchApplication
    private val store = app.agentPartnerStore
    private val workspaceId = MutableStateFlow<String?>(null)
    private val selectedGroupId = MutableStateFlow<String?>(null)

    val profiles: StateFlow<List<AgentProfileEntity>> = workspaceId
        .flatMapLatest { store.observeProfiles(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<AgentGroupEntity>> = workspaceId
        .flatMapLatest { store.observeGroups(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedGroup: StateFlow<AgentGroupEntity?> = selectedGroupId
        .flatMapLatest { id -> if (id == null) flowOf(null) else groupsFlow(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val groupMessages: StateFlow<List<AgentGroupMessageEntity>> = selectedGroupId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else store.observeGroupMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setWorkspace(id: String) {
        if (workspaceId.value != id) {
            workspaceId.value = id
            selectedGroupId.value = null
        }
    }

    fun selectGroup(id: String?) {
        selectedGroupId.value = id
    }

    fun clearMessage() {
        _message.value = null
    }

    fun createProfile(name: String, identity: String, soul: String, user: String?) = viewModelScope.launch {
        runCatching {
            val now = System.currentTimeMillis()
            store.saveProfile(
                AgentProfileEntity(
                    id = UUID.randomUUID().toString(),
                    workspaceId = workspaceId.value,
                    name = name.trim(),
                    avatarUri = null,
                    identityMarkdown = identity,
                    soulMarkdown = soul,
                    userMarkdown = user,
                    systemPrompt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }.onSuccess { _message.value = "伙伴已创建" }
            .onFailure { _message.value = it.message ?: "创建伙伴失败" }
    }

    fun createGroup(name: String, managerId: String, memberIds: List<String>, policy: String) = viewModelScope.launch {
        runCatching {
            store.createGroup(
                workspaceId = workspaceId.value,
                name = name.trim(),
                description = null,
                managerAgentId = managerId,
                memberAgentIds = memberIds,
                turnPolicy = policy
            )
        }.onSuccess { _message.value = "群聊已创建" }
            .onFailure { _message.value = it.message ?: "创建群聊失败" }
    }

    fun sendGroupMessage(content: String) = viewModelScope.launch {
        val groupId = selectedGroupId.value ?: run {
            _message.value = "请先选择群聊"
            return@launch
        }
        runCatching {
            store.appendGroupMessage(groupId, "USER", null, content.trim())
        }.onSuccess { _message.value = null }
            .onFailure { _message.value = it.message ?: "发送群消息失败" }
    }

    private fun groupsFlow(id: String) = kotlinx.coroutines.flow.flow {
        emit(groups.value.firstOrNull { it.id == id })
    }
}
