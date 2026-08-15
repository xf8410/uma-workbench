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
import com.uma.workbench.hlpatch.HlpatchCapabilityReport
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.network.NetworkState
import com.uma.workbench.protocol.*
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

    val workspaces = workspaceManager.observeWorkspaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _currentWorkspaceId = MutableStateFlow<String?>(null)
    val currentWorkspace: StateFlow<WorkspaceEntity?> = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf<WorkspaceEntity?>(null) else flow<WorkspaceEntity?> { emit(db.workspaces().get(id)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val projects = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<ProjectEntity>()) else workspaceManager.observeProjects(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFiles = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<RecentFileEntity>()) else workspaceManager.observeRecentFiles(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val openTabs = _currentWorkspaceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<OpenTabEntity>()) else db.openTabs().observe(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId
    private val _fileContent = MutableStateFlow<String?>(null)
    val fileContent: StateFlow<String?> = _fileContent

    val conversations = repository.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val messages = _currentConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<MessageEntity>()) else repository.messages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val networkState = app.networkState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkState.ONLINE)
    private val hlpatchClient = HlpatchClient(db)
    val hlpatchState = MutableStateFlow(hlpatchClient.state)
    val hlpatchCapabilities = MutableStateFlow(HlpatchCapabilityReport())

    private val sessionManager = SessionManager(db)
    private val protocolHistoryStore = ProtocolHistoryStore(application)
    private val protocolHistoryTimeline = ProtocolHistoryTimeline(protocolHistoryStore::all)
    private val protocolHistoryInspector = ProtocolHistoryInspector()
    private val protocolSender = ProtocolSender(application, db, hlpatchClient, protocolHistoryStore)
    private val sidHealthProbe = SidHealthProbe()

    val activeSession = sessionManager.activeSession
    val protocolLogs: StateFlow<List<ProtocolLogEntry>> = protocolSender.logs
    val protocolHistory: StateFlow<List<ProtocolHistoryRecord>> = protocolHistoryTimeline.records
    val protocolHistoryLoadState: StateFlow<ProtocolHistoryLoadState> = protocolHistoryTimeline.loadState
    val selectedProtocolHistoryIds: StateFlow<List<String>> = protocolHistoryInspector.selectedIds
    val dumpState: MutableStateFlow<String> = MutableStateFlow("")
    val sidHealthState: MutableStateFlow<SidHealthCheckState> = MutableStateFlow(SidHealthCheckState())

    init { reloadProtocolHistory() }

    fun reloadProtocolHistory() = viewModelScope.launch { protocolHistoryTimeline.reload() }
    fun toggleProtocolHistorySelection(id: String) = protocolHistoryInspector.toggle(id)
    fun clearProtocolHistorySelection() = protocolHistoryInspector.clear()
    fun protocolHistoryDetail(record: ProtocolHistoryRecord) = protocolHistoryInspector.detail(record)
    fun protocolHistoryDiff() = protocolHistoryInspector.diff(protocolHistory.value)

    fun createWorkspace(name: String) = viewModelScope.launch {
        val ws = workspaceManager.create(name)
        openWorkspace(ws.id)
    }
    fun openWorkspace(id: String) = viewModelScope.launch { workspaceManager.open(id); _currentWorkspaceId.value = id }
    fun closeWorkspace() { _currentWorkspaceId.value = null }
    fun addProject(name: String, sourceUri: String?) = viewModelScope.launch {
        _currentWorkspaceId.value?.let { workspaceManager.addProject(it, name, sourceUri, null) }
    }

    fun openFile(uri: String, name: String) = viewModelScope.launch {
        val wsId = _currentWorkspaceId.value ?: return@launch
        workspaceManager.recordRecentFile(wsId, uri, name)
        val tabId = UUID.randomUUID().toString()
        db.openTabs().upsert(OpenTabEntity(id = tabId, workspaceId = wsId, uri = uri, title = name, sortOrder = openTabs.value.size))
        _activeTabId.value = tabId
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
    fun importFile(uris: List<Uri> = emptyList()) = viewModelScope.launch {
        uris.forEach { uri -> openFile(uri.toString(), uri.lastPathSegment ?: uri.toString()) }
    }

    fun newConversation() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(id = UUID.randomUUID().toString(), title = "新对话", createdAt = now, updatedAt = now)
        repository.createConversation(conv)
        _currentConversationId.value = conv.id
    }
    fun sendAgentMessage(text: String) = viewModelScope.launch {
        val convId = _currentConversationId.value ?: run { newConversation(); return@launch }
        val now = System.currentTimeMillis()
        repository.addMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = convId, runId = null, requestId = null, sequence = repository.nextMessageSequence(convId), role = "user", content = text, status = "COMPLETE", createdAt = now))
        repository.addMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = convId, runId = null, requestId = null, sequence = repository.nextMessageSequence(convId), role = "assistant", content = "已收到：$text\n\n（AI Provider 尚未接入，当前为占位回复）", status = "COMPLETE", createdAt = System.currentTimeMillis()))
    }

    fun connectHlpatch() = viewModelScope.launch {
        hlpatchState.value = HlpatchClient.ConnectionState.CONNECTING
        hlpatchClient.health()
        hlpatchState.value = hlpatchClient.state
    }

    fun discoverHlpatchCapabilities() = viewModelScope.launch {
        hlpatchCapabilities.value = HlpatchCapabilityReport(running = true)
        hlpatchCapabilities.value = hlpatchClient.discoverCapabilities()
        hlpatchState.value = hlpatchClient.state
    }

    fun dumpSid() = viewModelScope.launch {
        dumpState.value = "正在连接 hlpatch…"
        val health = hlpatchClient.health()
        if (!health.ok) { dumpState.value = "hlpatch 未连接"; return@launch }
        dumpState.value = "正在读取 /summary…"
        val status = hlpatchClient.status()
        if (!status.ok) { dumpState.value = "读取失败"; return@launch }
        val sid = extractJsonField(status.body, "sid") ?: extractJsonField(status.body, "SID")
        val vid = extractJsonField(status.body, "viewer_id") ?: extractJsonField(status.body, "viewerId")
        if (sid != null) {
            val headers = buildMap<String, String> {
                put("APP-VER", extractJsonField(status.body, "app_ver") ?: "2.29.0")
                extractJsonField(status.body, "res_ver")?.let { put("RES-VER", it) }
                extractJsonField(status.body, "device_id")?.let { put("Device-Id", it) }
            }
            sessionManager.importFromHlpatch(sid, vid?.toLongOrNull() ?: 0L, headers)
            dumpState.value = "已 dump SID: $sid"
        } else dumpState.value = "未找到 SID"
    }

    fun checkSidHealth(sid: String, viewerId: Long?) = viewModelScope.launch {
        val exactSid = sid
        val exactViewerId = viewerId ?: 0L
        sidHealthState.value = SidHealthCheckState(running = true, checkedSid = exactSid, viewerId = viewerId)
        val active = activeSession.value
        val session = if (active != null && active.sid == exactSid && active.viewerId == exactViewerId) active else GameSession(
            sid = exactSid,
            viewerId = exactViewerId,
            accountToken = null,
            inheritCode = null,
            appVer = active?.appVer ?: "2.29.0",
            resVer = active?.resVer,
            resVerHash = active?.resVerHash,
            deviceId = active?.deviceId,
            deviceName = active?.deviceName,
            platformOsVersion = active?.platformOsVersion,
            capturedAt = System.currentTimeMillis(),
            source = SessionSource.MANUAL_INPUT,
            bound = exactViewerId > 0
        )
        runCatching { sidHealthProbe.probe(session, protocolSender::sendViaHlpatch) }
            .onSuccess { result -> sidHealthState.value = SidHealthCheckState(checkedSid = exactSid, viewerId = viewerId, result = result) }
            .onFailure { error -> sidHealthState.value = SidHealthCheckState(checkedSid = exactSid, viewerId = viewerId, error = error.stackTraceToString()) }
        protocolHistoryTimeline.reload()
    }

    fun sendProtocolRequest(endpoint: String, sid: String, viewerId: Long?, body: String, channel: Int) = viewModelScope.launch {
        val ep = GameEndpoint.entries.find { it.path == endpoint } ?: GameEndpoint.LOGIN
        val req = GameRequest(ep, sid.ifBlank { null }, viewerId, body, headers = sessionManager.buildHeaders(activeSession.value))
        runCatching {
            when (channel) {
                2 -> protocolSender.sendViaHlpatch(req)
                else -> throw IllegalStateException("直连服务器地址尚未由真实配置提供；请求未发送，完整输入仍保留")
            }
        }
        protocolHistoryTimeline.reload()
    }

    private fun extractJsonField(json: String, field: String): String? {
        val patterns = listOf("\"$field\"", "'$field'", "$field:")
        for (pattern in patterns) {
            val idx = json.indexOf(pattern, ignoreCase = true)
            if (idx >= 0) {
                val after = json.substring(idx + pattern.length).trimStart(' ', ':', '"', '\'')
                val end = after.indexOfFirst { it == '"' || it == '\'' || it == ',' || it == '}' || it == '\n' }
                val value = if (end >= 0) after.substring(0, end) else after
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }
}
