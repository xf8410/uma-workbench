package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.agent.*
import com.uma.workbench.data.ConversationEntity
import com.uma.workbench.data.MessageEntity
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as WorkbenchApplication).repository
    private val catalogStore = AiProviderCatalogStore(application)
    private val _catalog = MutableStateFlow(catalogStore.load())
    val catalog: StateFlow<AiProviderCatalog> = _catalog
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId
    val messages = _conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun selectedProfile(): AiProviderProfile? {
        _catalog.value = catalogStore.load()
        val selection = _catalog.value.defaultModel ?: return null
        return _catalog.value.providers.firstOrNull { it.id == selection.providerId }
    }
    private val controller = AiGenerationController(viewModelScope, CatalogAiStreamingProvider(::selectedProfile))
    val generation: StateFlow<AiGenerationState> = controller.state

    fun refreshConfiguration() { _catalog.value = catalogStore.load() }
    fun newConversation() { _conversationId.value = null }

    fun send(completeText: String) {
        if (completeText.isBlank() || generation.value.canInterrupt) return
        refreshConfiguration()
        val selection = _catalog.value.defaultModel ?: return
        viewModelScope.launch {
            val conversationId = ensureConversation(completeText)
            val history = messages.value.map { AiPromptMessage(it.role.lowercase(), it.content) } + AiPromptMessage("user", completeText)
            repository.addMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, null, null, repository.nextMessageSequence(conversationId), "user", completeText, createdAt = System.currentTimeMillis()))
            val requestId = UUID.randomUUID().toString()
            if (!controller.send(AiGenerationRequest(requestId, history, selection.modelId))) return@launch
            val terminal = generation.first { it.requestId == requestId && it.phase != AiGenerationPhase.GENERATING && it.phase != AiGenerationPhase.IDLE }
            repository.addMessage(MessageEntity(
                id = UUID.randomUUID().toString(), conversationId = conversationId, runId = requestId, requestId = requestId,
                sequence = repository.nextMessageSequence(conversationId), role = "assistant", content = terminal.completeText,
                status = terminal.phase.name, createdAt = System.currentTimeMillis(),
                tokenCount = terminal.usage?.totalTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), modelUsed = terminal.model ?: selection.modelId
            ))
        }
    }

    fun interrupt() { controller.interrupt() }

    private suspend fun ensureConversation(firstText: String): String {
        _conversationId.value?.let { return it }
        val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
        repository.createConversation(ConversationEntity(id, firstText.take(40), now, now))
        _conversationId.value = id
        return id
    }
}
