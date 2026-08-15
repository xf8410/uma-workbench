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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val repository = app.repository
    private val catalogStore = AiProviderCatalogStore(application)
    private val _catalog = MutableStateFlow(catalogStore.load()); val catalog: StateFlow<AiProviderCatalog> = _catalog
    private val _conversationId = MutableStateFlow<String?>(null); val conversationId: StateFlow<String?> = _conversationId
    val messages = _conversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeDocument: StateFlow<ActiveWorkspaceDocument?> = ActiveWorkspaceDocumentBridge.document
    private val _attachments = MutableStateFlow<List<WorkspaceContextAttachment>>(emptyList()); val attachments = _attachments.asStateFlow()
    private val _attachmentMessage = MutableStateFlow(""); val attachmentMessage = _attachmentMessage.asStateFlow()
    private val _loadingAttachment = MutableStateFlow(false); val loadingAttachment = _loadingAttachment.asStateFlow()
    private val _searchPage = MutableStateFlow<WorkspaceSearchPage?>(null); val searchPage = _searchPage.asStateFlow()
    private val _searching = MutableStateFlow(false); val searching = _searching.asStateFlow()
    private val _generation = MutableStateFlow(AiGenerationState()); val generation = _generation.asStateFlow()
    private val _agentRounds = MutableStateFlow<List<ReadonlyAgentRound>>(emptyList()); val agentRounds = _agentRounds.asStateFlow()
    private var activeAgentJob: Job? = null
    private var lastSearchQuery = ""; private var lastSearchCaseSensitive = false
    private val workspaceSearch = WorkspaceReadonlySearch(WorkspaceDocumentTextReader { readCompleteText(it.uri) }, WorkspaceSearchLimits(maxDocuments = 100, maxCharactersPerDocument = 2_000_000, maxMatchesPerPage = 100))
    private val provider = CatalogAiStreamingProvider(::selectedProfile)

    private fun selectedProfile(): AiProviderProfile? { _catalog.value = catalogStore.load(); val selection = _catalog.value.defaultModel ?: return null; return _catalog.value.providers.firstOrNull { it.id == selection.providerId } }
    fun refreshConfiguration() { _catalog.value = catalogStore.load() }
    fun newConversation() { if (activeAgentJob?.isActive == true) return; _conversationId.value = null; _attachments.value = emptyList(); _attachmentMessage.value = ""; _searchPage.value = null; _agentRounds.value = emptyList(); _generation.value = AiGenerationState() }

    fun searchWorkspace(query: String, caseSensitive: Boolean = false, offset: Int = 0) {
        val document = activeDocument.value ?: run { _attachmentMessage.value = "当前没有打开工作区文件，无法确定搜索范围"; return }
        if (query.isBlank()) { _attachmentMessage.value = "搜索词不能为空"; return }
        viewModelScope.launch { _searching.value = true; lastSearchQuery = query; lastSearchCaseSensitive = caseSensitive
            runCatching { val recent = app.database.recentFiles().observe(document.workspaceId).first(); val sources = repository.sources().first().filter { it.workspaceId == document.workspaceId }; workspaceSearch.search(buildList { add(WorkspaceSearchDocument(document.workspaceId, document.uri, document.title)); recent.forEach { add(WorkspaceSearchDocument(it.workspaceId, it.uri, it.name)) }; sources.forEach { add(WorkspaceSearchDocument(document.workspaceId, it.uri, it.name)) } }, query, offset, caseSensitive) }
                .onSuccess { _searchPage.value = it; _attachmentMessage.value = "搜索得到 ${it.totalMatches} 个匹配；已扫描 ${it.scannedDocuments}/${it.availableDocuments} 个文档" }
                .onFailure { _attachmentMessage.value = it.stackTraceToString() }; _searching.value = false }
    }
    fun nextSearchPage() { _searchPage.value?.nextOffset?.let { searchWorkspace(lastSearchQuery, lastSearchCaseSensitive, it) } }
    fun attachSearchMatch(match: WorkspaceSearchMatch) { if (_loadingAttachment.value) return; viewModelScope.launch { _loadingAttachment.value = true; runCatching { WorkspaceContextAttachmentFactory.fromText(match.workspaceId, match.uri, match.title, readCompleteText(match.uri), match.lineNumber, match.lineNumber) }.onSuccess { _attachments.value += it; _attachmentMessage.value = "已附加 ${it.title} L${it.startLine}" }.onFailure { _attachmentMessage.value = it.stackTraceToString() }; _loadingAttachment.value = false } }
    fun attachCurrentFileRange(startLine: Int, endLine: Int) { if (_loadingAttachment.value) return; val d = activeDocument.value ?: run { _attachmentMessage.value = "当前没有打开文件"; return }; if (startLine < 1 || endLine < startLine) { _attachmentMessage.value = "请输入有效行范围"; return }; viewModelScope.launch { _loadingAttachment.value = true; runCatching { WorkspaceContextAttachmentFactory.fromText(d.workspaceId, d.uri, d.title, readCompleteText(d.uri), startLine, endLine) }.onSuccess { _attachments.value += it; _attachmentMessage.value = "已附加 ${it.title} L${it.startLine}-${it.endLine}" }.onFailure { _attachmentMessage.value = it.stackTraceToString() }; _loadingAttachment.value = false } }
    private suspend fun readCompleteText(uri: String) = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(Uri.parse(uri))?.use { String(it.readBytes(), Charsets.UTF_8) } ?: error("无法打开 $uri") }
    fun removeAttachment(id: String) { _attachments.value = _attachments.value.filterNot { it.id == id }; _attachmentMessage.value = "" }

    fun send(completeText: String) {
        if (completeText.isBlank() || activeAgentJob?.isActive == true) return
        refreshConfiguration(); val selection = _catalog.value.defaultModel ?: return
        val document = activeDocument.value ?: run { _attachmentMessage.value = "只读 Agent 需要当前工作区"; return }
        activeAgentJob = viewModelScope.launch {
            val selectedAttachments = _attachments.value.toList(); val exactPrompt = WorkspaceContextPromptComposer.compose(completeText, selectedAttachments); val metadata = WorkspaceContextPromptComposer.metadataJson(selectedAttachments)
            val conversation = ensureConversation(completeText); val history = messages.value.map { AiPromptMessage(it.role.lowercase(), it.content) } + AiPromptMessage("user", exactPrompt)
            repository.addMessage(MessageEntity(UUID.randomUUID().toString(), conversation, null, null, repository.nextMessageSequence(conversation), "user", completeText, createdAt = System.currentTimeMillis(), toolCallsJson = metadata))
            val requestId = UUID.randomUUID().toString(); _attachments.value = emptyList(); _agentRounds.value = emptyList(); _generation.value = AiGenerationState(AiGenerationPhase.GENERATING, requestId, model = selection.modelId)
            val dataSource = AndroidReadonlyAgentToolDataSource(app, app.database, document.workspaceId, { activeDocument.value })
            val loop = ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(dataSource))
            try {
                val result = loop.run(AiGenerationRequest(requestId, history, selection.modelId, ReadonlyAgentToolSchemas.openAiCompatible), object : ReadonlyAgentObserver {
                    override fun onModelEvent(round: Int, event: AiStreamEvent) { when (event) {
                        is AiStreamEvent.TextDelta -> _generation.value = _generation.value.copy(completeText = _generation.value.completeText + event.completeDelta)
                        is AiStreamEvent.Model -> _generation.value = _generation.value.copy(model = event.model)
                        else -> Unit
                    } }
                    override fun onRoundCompleted(round: ReadonlyAgentRound) { _agentRounds.value += round; if (round.toolCalls.isNotEmpty()) _generation.value = _generation.value.copy(completeText = "", toolCalls = round.toolCalls) }
                })
                _generation.value = _generation.value.copy(phase = AiGenerationPhase.COMPLETED, completeText = result.completeAnswer, usage = result.usage, model = result.model)
                repository.addMessage(MessageEntity(UUID.randomUUID().toString(), conversation, requestId, requestId, repository.nextMessageSequence(conversation), "assistant", result.completeAnswer, "COMPLETED", System.currentTimeMillis(), toolCallsJson = AgentRunPresentation.toJson(result.rounds), tokenCount = result.usage.totalTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), modelUsed = result.model ?: selection.modelId))
            } catch (cancelled: CancellationException) { _generation.value = _generation.value.copy(phase = AiGenerationPhase.CANCELLED); throw cancelled }
            catch (error: Throwable) { _generation.value = _generation.value.copy(phase = AiGenerationPhase.FAILED, error = error.stackTraceToString()) }
            finally { activeAgentJob = null }
        }
    }

    fun interrupt() { activeAgentJob?.cancel(CancellationException("用户停止 Agent")) }
    private suspend fun ensureConversation(firstText: String): String { _conversationId.value?.let { return it }; val now = System.currentTimeMillis(); val id = UUID.randomUUID().toString(); repository.createConversation(ConversationEntity(id, firstText.take(40), now, now, workspaceId = activeDocument.value?.workspaceId)); _conversationId.value = id; return id }
}

object AgentRunPresentation {
    fun toJson(rounds: List<ReadonlyAgentRound>): String = buildString { append('['); rounds.forEachIndexed { i, r -> if (i > 0) append(','); append("{\"round\":${r.index},\"calls\":${r.toolCalls.size},\"outcomes\":${r.toolOutcomes.size}}") }; append(']') }
}
