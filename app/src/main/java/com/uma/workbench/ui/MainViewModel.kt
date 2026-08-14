package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.data.ConversationEntity
import com.uma.workbench.data.MessageEntity
import com.uma.workbench.data.WorkItemEntity
import com.uma.workbench.network.NetworkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val repository = app.repository
    private val selectedId = MutableStateFlow<String?>(null)

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val selectedConversationId: StateFlow<String?> = selectedId.asStateFlow()
    val messages: StateFlow<List<MessageEntity>> = selectedId.filterNotNull()
        .flatMapLatest(repository::messages)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workItems: StateFlow<List<WorkItemEntity>> = repository.workItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val network: StateFlow<NetworkState> = app.networkMonitor.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkState.SWITCHING)

    init {
        viewModelScope.launch {
            conversations.filter { it.isNotEmpty() }.firstOrNull()?.let { list ->
                if (selectedId.value == null) selectedId.value = list.first().id
            }
        }
    }

    fun createConversation() = viewModelScope.launch { selectedId.value = repository.createConversation() }
    fun selectConversation(id: String) { selectedId.value = id }
    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val id = selectedId.value ?: repository.createConversation().also { selectedId.value = it }
            repository.queueUserMessage(id, clean)
        }
    }
}
