package com.uma.workbench.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.agent.*
import com.uma.workbench.data.ConversationEntity
import com.uma.workbench.data.MessageEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val repository = app.repository
    private val catalogStore = AiProviderCatalogStore(application)
    private val _catalog = MutableStateFlow(catalogStore.load())
    val catalog: StateFlow<AiProviderCatalog> = _catalog
    private val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId
    val messages = _conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeDocument: StateFlow<ActiveWorkspaceDocument?> = ActiveWorkspaceDocumentBridge.document
    private val _attachments = MutableStateFlow<List<WorkspaceContextAttachment>>(emptyList())
    val attachments: StateFlow<List<WorkspaceContextAttachment>> = _attachments.asStateFlow()
    private val _attachmentMessage = MutableStateFlow("")
    val attachmentMessage: StateFlow<String> = _attachmentMessage.asStateFlow()
    private val _loadingAttachment = MutableStateFlow(false)
    val loadingAttachment: StateFlow<Boolean> = _loadingAttachment.asStateFlow()

    private fun selectedProfile(): AiProviderProfile? {
        _catalog.value = catalogStore.load()
        val selection = _catalog.value.defaultModel ?: return null
        return _catalog.value.providers.firstOrNull { it.id == selection.providerId }
    }
    private val controller = AiGenerationController(viewModelScope, CatalogAiStreamingProvider(::selectedProfile))
    val generation: StateFlow<AiGenerationState> = controller.state

    fun refreshConfiguration() { _catalog.value = catalogStore.load() }
    fun newConversation() {
        _conversationId.value = null
        _attachments.value = emptyList()
        _attachmentMessage.value = ""
    }

    fun attachCurrentFileRange(startLine: Int, endLine: Int) {
        if (_loadingAttachment.value) return
        val document = activeDocument.value ?: run {
            _attachmentMessage.value = "当前没有打开文件"
            return
        }
        if (startLine < 1 || endLine < startLine) {
            _attachmentMessage.value = "请输入有效行范围"
            return
        }
        viewModelScope.launch {
            _loadingAttachment.value = true
            _attachmentMessage.value = "正在读取 ${document.title} 的完整文本并提取 L$startLine-L$endLine"
            runCatching {
                val completeText = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(Uri.parse(document.uri))?.use { input ->
                        String(input.readBytes(), Charsets.UTF_8)
                    } ?: error("无法打开 ${document.uri}")
                }
                WorkspaceContextAttachmentFactory.fromText(
                    workspaceId = document.workspaceId,
                    uri = document.uri,
                    title = document.title,
                    completeText = completeText,
                    startLine = startLine,
                    endLine = endLine
                )
            }.onSuccess { attachment ->
                _attachments.value = _attachments.value + attachment
                _attachmentMessage.value = "已附加 ${attachment.title} L${attachment.startLine}-L${attachment.endLine}，实际发送 ${attachment.sentCharacterCount} 字符；完整文件 ${attachment.completeCharacterCount} 字符"
            }.onFailure { error ->
                _attachmentMessage.value = error.stackTraceToString()
            }
            _loadingAttachment.value = false
        }
    }

    fun removeAttachment(id: String) {
        _attachments.value = _attachments.value.filterNot { it.id == id }
        _attachmentMessage.value = ""
    }

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
        repository.createConversation(ConversationEntity(id, firstText.take(40), now, now, workspaceId = activeDocument.value?.workspaceId))
        _conversationId.value = id
        return id
    }
}
