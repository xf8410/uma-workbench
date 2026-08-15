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
    private val _searchPage = MutableStateFlow<WorkspaceSearchPage?>(null)
    val searchPage: StateFlow<WorkspaceSearchPage?> = _searchPage.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var lastSearchQuery = ""
    private var lastSearchCaseSensitive = false

    private val workspaceSearch = WorkspaceReadonlySearch(
        reader = WorkspaceDocumentTextReader { document -> readCompleteText(document.uri) },
        limits = WorkspaceSearchLimits(maxDocuments = 100, maxCharactersPerDocument = 2_000_000, maxMatchesPerPage = 100)
    )

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
        _searchPage.value = null
    }

    fun searchWorkspace(query: String, caseSensitive: Boolean = false, offset: Int = 0) {
        val document = activeDocument.value ?: run {
            _attachmentMessage.value = "当前没有打开工作区文件，无法确定搜索范围"
            return
        }
        if (query.isBlank()) {
            _attachmentMessage.value = "搜索词不能为空"
            return
        }
        viewModelScope.launch {
            _searching.value = true
            lastSearchQuery = query
            lastSearchCaseSensitive = caseSensitive
            runCatching {
                val recent = app.database.recentFiles().observe(document.workspaceId).first()
                val sources = repository.sources().first().filter { it.workspaceId == document.workspaceId }
                val documents = buildList {
                    add(WorkspaceSearchDocument(document.workspaceId, document.uri, document.title))
                    recent.forEach { add(WorkspaceSearchDocument(it.workspaceId, it.uri, it.name)) }
                    sources.forEach { source -> add(WorkspaceSearchDocument(document.workspaceId, source.uri, source.name)) }
                }
                workspaceSearch.search(documents, query, offset, caseSensitive)
            }.onSuccess { page ->
                _searchPage.value = page
                _attachmentMessage.value = buildString {
                    append("搜索得到 ${page.totalMatches} 个匹配；已扫描 ${page.scannedDocuments}/${page.availableDocuments} 个文档")
                    if (!page.isCompleteDocumentScan) append("；存在未完整扫描或读取失败的文档，请查看搜索状态")
                }
            }.onFailure { error -> _attachmentMessage.value = error.stackTraceToString() }
            _searching.value = false
        }
    }

    fun nextSearchPage() {
        val offset = _searchPage.value?.nextOffset ?: return
        searchWorkspace(lastSearchQuery, lastSearchCaseSensitive, offset)
    }

    fun attachSearchMatch(match: WorkspaceSearchMatch) {
        if (_loadingAttachment.value) return
        viewModelScope.launch {
            _loadingAttachment.value = true
            runCatching {
                val completeText = readCompleteText(match.uri)
                WorkspaceContextAttachmentFactory.fromText(
                    workspaceId = match.workspaceId,
                    uri = match.uri,
                    title = match.title,
                    completeText = completeText,
                    startLine = match.lineNumber,
                    endLine = match.lineNumber
                )
            }.onSuccess { attachment ->
                _attachments.value = _attachments.value + attachment
                _attachmentMessage.value = "已附加搜索结果 ${attachment.title} L${attachment.startLine}；实际发送完整匹配行 ${attachment.sentCharacterCount} 字符"
            }.onFailure { error -> _attachmentMessage.value = error.stackTraceToString() }
            _loadingAttachment.value = false
        }
    }

    fun attachCurrentFileRange(startLine: Int, endLine: Int) {
        if (_loadingAttachment.value) return
        val document = activeDocument.value ?: run { _attachmentMessage.value = "当前没有打开文件"; return }
        if (startLine < 1 || endLine < startLine) { _attachmentMessage.value = "请输入有效行范围"; return }
        viewModelScope.launch {
            _loadingAttachment.value = true
            _attachmentMessage.value = "正在读取 ${document.title} 的完整文本并提取 L$startLine-L$endLine"
            runCatching {
                WorkspaceContextAttachmentFactory.fromText(document.workspaceId, document.uri, document.title, readCompleteText(document.uri), startLine, endLine)
            }.onSuccess { attachment ->
                _attachments.value = _attachments.value + attachment
                _attachmentMessage.value = "已附加 ${attachment.title} L${attachment.startLine}-L${attachment.endLine}，实际发送 ${attachment.sentCharacterCount} 字符；完整文件 ${attachment.completeCharacterCount} 字符"
            }.onFailure { error -> _attachmentMessage.value = error.stackTraceToString() }
            _loadingAttachment.value = false
        }
    }

    private suspend fun readCompleteText(uri: String): String = withContext(Dispatchers.IO) {
        app.contentResolver.openInputStream(Uri.parse(uri))?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: error("无法打开 $uri")
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
            val selectedAttachments = _attachments.value.toList()
            val exactPrompt = WorkspaceContextPromptComposer.compose(completeText, selectedAttachments)
            val attachmentMetadata = WorkspaceContextPromptComposer.metadataJson(selectedAttachments)
            val conversationId = ensureConversation(completeText)
            val history = messages.value.map { AiPromptMessage(it.role.lowercase(), it.content) } + AiPromptMessage("user", exactPrompt)
            repository.addMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, null, null, repository.nextMessageSequence(conversationId), "user", completeText, createdAt = System.currentTimeMillis(), toolCallsJson = attachmentMetadata))
            val requestId = UUID.randomUUID().toString()
            if (!controller.send(AiGenerationRequest(requestId, history, selection.modelId))) return@launch
            _attachments.value = emptyList()
            _attachmentMessage.value = if (selectedAttachments.isEmpty()) "" else "本轮 ${selectedAttachments.size} 个上下文附件已实际注入请求"
            val terminal = generation.first { it.requestId == requestId && it.phase != AiGenerationPhase.GENERATING && it.phase != AiGenerationPhase.IDLE }
            repository.addMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, requestId, requestId, repository.nextMessageSequence(conversationId), "assistant", terminal.completeText, terminal.phase.name, System.currentTimeMillis(), tokenCount = terminal.usage?.totalTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), modelUsed = terminal.model ?: selection.modelId))
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
