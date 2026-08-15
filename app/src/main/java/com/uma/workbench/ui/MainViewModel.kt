package com.uma.workbench.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.data.*
import com.uma.workbench.data.OpenTabEntity
import com.uma.workbench.data.ProjectEntity
import com.uma.workbench.data.RecentFileEntity
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.network.NetworkState
import com.uma.workbench.workspace.WorkspaceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val repository = app.repository
    private val db = app.database
    private val workspaceManager = WorkspaceManager(db, application)

    // ── 工作区 (001-040) ──
    val workspaces = workspaceManager.observeWorkspaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _currentWorkspaceId = MutableStateFlow<String?>(null)
    val currentWorkspace: StateFlow<WorkspaceEntity?> = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf<WorkspaceEntity?>(null) else flow<WorkspaceEntity?> { emit(db.workspaces().get(id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val projects = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<ProjectEntity>()) else workspaceManager.observeProjects(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFiles = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<RecentFileEntity>()) else workspaceManager.observeRecentFiles(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 标签页 (051-054) ──
    val openTabs = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<OpenTabEntity>()) else db.openTabs().observe(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    private val _fileContent = MutableStateFlow<String?>(null)
    val fileContent: StateFlow<String?> = _fileContent

    // ── Agent 对话 (241-270) ──
    val conversations = repository.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val messages = _currentConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<MessageEntity>()) else repository.messages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 网络 & hlpatch (361-370) ──
    val networkState = app.networkState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkState.ONLINE)
    private val hlpatchClient = HlpatchClient(db)
    val hlpatchState = MutableStateFlow(hlpatchClient.state)

    // ── 工作区操作 ──
    fun createWorkspace(name: String) = viewModelScope.launch {
        val ws = workspaceManager.create(name)
        openWorkspace(ws.id)
    }

    fun openWorkspace(id: String) = viewModelScope.launch {
        workspaceManager.open(id)
        _currentWorkspaceId.value = id
    }

    fun closeWorkspace() { _currentWorkspaceId.value = null }

    fun addProject(name: String, sourceUri: String?) = viewModelScope.launch {
        _currentWorkspaceId.value?.let { workspaceManager.addProject(it, name, sourceUri, null) }
    }

    // ── 文件操作 (081-100) ──
    fun openFile(uri: String, name: String) = viewModelScope.launch {
        val wsId = _currentWorkspaceId.value ?: return@launch
        workspaceManager.recordRecentFile(wsId, uri, name)
        val tabId = UUID.randomUUID().toString()
        val tab = OpenTabEntity(id = tabId, workspaceId = wsId, uri = uri, title = name, sortOrder = openTabs.value.size)
        db.openTabs().upsert(tab)
        _activeTabId.value = tabId
        // 读取文件内容
        runCatching {
            val pfd = app.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return@runCatching
            pfd.use { fd ->
                val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
                _fileContent.value = String(bytes, Charsets.UTF_8)
            }
        }.onFailure { _fileContent.value = "读取失败: ${it.message}" }
    }

    fun selectTab(id: String) { _activeTabId.value = id }
    fun closeTab(id: String) = viewModelScope.launch { db.openTabs().delete(id); if (_activeTabId.value == id) _activeTabId.value = openTabs.value.firstOrNull()?.id }

    // 文件导入启动器回调
    fun importFile(uris: List<Uri> = emptyList()) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        uris.forEach { uri ->
            runCatching {
                val name = uri.lastPathSegment ?: uri.toString().takeLast(30)
                openFile(uri.toString(), name)
            }
        }
    }

    // ── Agent 对话 (241-270) ──
    fun newConversation() = viewModelScope.launch {
        val wsId = _currentWorkspaceId.value
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(id = UUID.randomUUID().toString(), title = "新对话", createdAt = now, updatedAt = now)
        repository.createConversation(conv)
        _currentConversationId.value = conv.id
    }

    fun sendAgentMessage(text: String) = viewModelScope.launch {
        val convId = _currentConversationId.value ?: run { newConversation(); return@launch }
        val now = System.currentTimeMillis()
        val seq = repository.nextMessageSequence(convId)
        repository.addMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = convId, runId = null, requestId = null, sequence = seq, role = "user", content = text, status = "COMPLETE", createdAt = now))
        // TODO: 实际 AI 调用 — 目前回显占位
        val replySeq = repository.nextMessageSequence(convId)
        repository.addMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = convId, runId = null, requestId = null, sequence = replySeq, role = "assistant", content = "已收到：$text\n\n（AI Provider 尚未接入，当前为占位回复）", status = "COMPLETE", createdAt = System.currentTimeMillis()))
    }

    // ── hlpatch 连接 (361-370) ──
    fun connectHlpatch() = viewModelScope.launch {
        hlpatchState.value = HlpatchClient.ConnectionState.CONNECTING
        val result = hlpatchClient.health()
        hlpatchState.value = hlpatchClient.state
    }
}
