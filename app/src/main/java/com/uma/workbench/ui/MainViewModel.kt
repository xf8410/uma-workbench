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
import com.uma.workbench.protocol.GameEndpoint
import com.uma.workbench.protocol.GameRequest
import com.uma.workbench.protocol.GameSession
import com.uma.workbench.protocol.PacketCrypto
import com.uma.workbench.protocol.ProtocolLogEntry
import com.uma.workbench.protocol.ProtocolSender
import com.uma.workbench.protocol.SessionManager
import com.uma.workbench.protocol.SidDumper
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

    // ── 育成协议 (协议面板) ──
    private val sessionManager = SessionManager(db)
    private val protocolSender = ProtocolSender(application, db, hlpatchClient)
    private val sidDumper = SidDumper(db, hlpatchClient, sessionManager)

    val activeSession = sessionManager.activeSession
    val protocolLogs: StateFlow<List<ProtocolLogEntry>> = protocolSender.logs
    val dumpState: MutableStateFlow<String> = MutableStateFlow("")

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

    // ── 育成协议操作 ──
    fun dumpSid() = viewModelScope.launch {
        dumpState.value = "正在连接 hlpatch…"
        val health = hlpatchClient.health()
        if (!health.ok) { dumpState.value = "hlpatch 未连接"; return@launch }
        dumpState.value = "正在读取 /summary…"
        val status = hlpatchClient.status()
        if (!status.ok) { dumpState.value = "读取失败"; return@launch }
        // 从 /summary 响应中提取 SID 和 viewer_id
        val sid = extractJsonField(status.body, "sid") ?: extractJsonField(status.body, "SID")
        val vid = extractJsonField(status.body, "viewer_id") ?: extractJsonField(status.body, "viewerId")
        if (sid != null) {
            val headers = buildMap<String, String> {
                put("APP-VER", extractJsonField(status.body, "app_ver") ?: "2.29.0")
                extractJsonField(status.body, "res_ver")?.let { put("RES-VER", it) }
                extractJsonField(status.body, "device_id")?.let { put("Device-Id", it) }
            }
            sessionManager.importFromHlpatch(sid, vid?.toLongOrNull() ?: 0L, headers)
            dumpState.value = "已 dump SID: ${sid.take(8)}…"
        } else {
            dumpState.value = "未找到 SID"
        }
    }

    fun sendProtocolRequest(endpoint: String, sid: String, viewerId: Long?, body: String, channel: Int) = viewModelScope.launch {
        val ep = GameEndpoint.entries.find { it.path == endpoint } ?: GameEndpoint.LOGIN
        val req = GameRequest(
            endpoint = ep,
            sid = sid.ifBlank { null },
            viewerId = viewerId,
            body = body,
            headers = sessionManager.buildHeaders(activeSession.value)
        )
        val ch = when (channel) { 0 -> com.uma.workbench.protocol.SendChannel.OKHTTP_DIRECT; 1 -> com.uma.workbench.protocol.SendChannel.OKHTTP_CUSTOM_TLS; else -> com.uma.workbench.protocol.SendChannel.HLPATCH_PROXY }
        val baseUrl = "https://game-server.example.com"
        val url = "$baseUrl/${ep.path}"
        runCatching {
            val resp = when (ch) {
                com.uma.workbench.protocol.SendChannel.OKHTTP_DIRECT -> protocolSender.sendDirect(url, req)
                com.uma.workbench.protocol.SendChannel.OKHTTP_CUSTOM_TLS -> protocolSender.sendCustomTls(url, req)
                com.uma.workbench.protocol.SendChannel.HLPATCH_PROXY -> protocolSender.sendViaHlpatch(req)
            }
        }.onFailure { /* 日志已记录 */ }
    }

    private fun extractJsonField(json: String, field: String): String? {
        val patterns = listOf("\"$field\"", "'$field'", "$field:")
        for (pattern in patterns) {
            val idx = json.indexOf(pattern, ignoreCase = true)
            if (idx >= 0) {
                val after = json.substring(idx + pattern.length).trimStart(' ', ':', '"', '\'')
                val end = after.indexOfFirst { it == '"' || it == '\'' || it == ',' || it == '}' || it == '\n' }
                val value = if (end >= 0) after.substring(0, end) else after.take(64)
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }
}
