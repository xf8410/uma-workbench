package com.uma.workbench.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.data.*
import com.uma.workbench.hlpatch.HlpatchCapabilityReport
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.network.NetworkState
import com.uma.workbench.protocol.*
import com.uma.workbench.workspace.WorkspaceManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.uma.workbench.agent.ActiveWorkspaceBridge

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WorkbenchApplication
    private val repository = app.repository
    private val db = app.database
    private val workspaceManager = WorkspaceManager(db, application)

    val workspaces = workspaceManager.observeWorkspaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _currentWorkspaceId = MutableStateFlow<String?>(null)
    val currentWorkspace: StateFlow<WorkspaceEntity?> = _currentWorkspaceId.flatMapLatest { id -> if (id == null) flowOf<WorkspaceEntity?>(null) else flow<WorkspaceEntity?> { emit(db.workspaces().get(id)) } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val projects = _currentWorkspaceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else workspaceManager.observeProjects(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentFiles = _currentWorkspaceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else workspaceManager.observeRecentFiles(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val openTabs = _currentWorkspaceId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.openTabs().observe(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _activeTabId = MutableStateFlow<String?>(null); val activeTabId: StateFlow<String?> = _activeTabId
    private val _fileContent = MutableStateFlow<String?>(null); val fileContent: StateFlow<String?> = _fileContent

    val conversations = repository.conversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val messages = _currentConversationId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val networkState = app.networkState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkState.ONLINE)
    private val hlpatchClient = HlpatchClient(db)
    val hlpatchState = MutableStateFlow(hlpatchClient.state)
    val hlpatchCapabilities = MutableStateFlow(HlpatchCapabilityReport())

    private val allSources = repository.sources()
    private val allWorkItems = repository.workItems()
    val importRows: StateFlow<List<ImportStatusRow>> = combine(_currentWorkspaceId, allSources, allWorkItems) { workspaceId, sources, workItems ->
        if (workspaceId == null) emptyList() else ImportPresentation.rows(sources.filter { it.workspaceId == workspaceId }, workItems.filter { it.workspaceId == workspaceId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val importMessage = MutableStateFlow("")

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
    val dumpState = MutableStateFlow("")
    val sidHealthState = MutableStateFlow(SidHealthCheckState())

    init { reloadProtocolHistory() }
    fun reloadProtocolHistory() = viewModelScope.launch { protocolHistoryTimeline.reload() }
    fun toggleProtocolHistorySelection(id: String) = protocolHistoryInspector.toggle(id)
    fun clearProtocolHistorySelection() = protocolHistoryInspector.clear()
    fun protocolHistoryDetail(record: ProtocolHistoryRecord) = protocolHistoryInspector.detail(record)
    fun protocolHistoryDiff() = protocolHistoryInspector.diff(protocolHistory.value)

    fun createWorkspace(name: String) = viewModelScope.launch { val ws = workspaceManager.create(name); openWorkspace(ws.id) }
    fun openWorkspace(id: String) = viewModelScope.launch { workspaceManager.open(id); _currentWorkspaceId.value = id; ActiveWorkspaceBridge.publish(id) }
    fun closeWorkspace() { _currentWorkspaceId.value = null; ActiveWorkspaceBridge.publish(null) }
    fun addProject(name: String, sourceUri: String?) = viewModelScope.launch { _currentWorkspaceId.value?.let { workspaceManager.addProject(it, name, sourceUri, null) } }

    fun openFile(uri: String, name: String) = viewModelScope.launch {
        val wsId = _currentWorkspaceId.value ?: return@launch
        workspaceManager.recordRecentFile(wsId, uri, name)
        val tabId = UUID.randomUUID().toString()
        db.openTabs().upsert(OpenTabEntity(tabId, wsId, uri, name, sortOrder = openTabs.value.size))
        _activeTabId.value = tabId
        runCatching { app.contentResolver.openInputStream(Uri.parse(uri))?.use { _fileContent.value = String(it.readBytes(), Charsets.UTF_8) } ?: error("无法打开文件") }.onFailure { _fileContent.value = "读取失败: ${it.stackTraceToString()}" }
    }
    fun selectTab(id: String) { _activeTabId.value = id }
    fun closeTab(id: String) = viewModelScope.launch { db.openTabs().delete(id); if (_activeTabId.value == id) _activeTabId.value = openTabs.value.firstOrNull()?.id }

    fun importAndIndex(uris: List<Uri>) = viewModelScope.launch {
        val workspaceId = _currentWorkspaceId.value ?: return@launch
        importMessage.value = "正在读取 ${uris.size} 个文件并计算完整 SHA-256…"
        var queued = 0
        val failures = mutableListOf<String>()
        uris.forEach { uri ->
            runCatching {
                runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val imported = withContext(Dispatchers.IO) { app.sourceImporter.importSource(uri) }
                val result = repository.queueImportedSource(imported.name, uri.toString(), imported.kind, imported.sha256, workspaceId, imported.size)
                app.workScheduler.scheduleAudit(result.workItemId)
                workspaceManager.recordRecentFile(workspaceId, uri.toString(), imported.name)
                queued++
            }.onFailure { failures += "$uri\n${it.stackTraceToString()}" }
        }
        importMessage.value = buildString {
            append("已加入索引队列 $queued 个文件")
            if (failures.isNotEmpty()) append("；${failures.size} 个失败：\n${failures.joinToString("\n")}")
        }
    }

    fun retryImport(sourceId: String) = viewModelScope.launch {
        val item = allWorkItems.first().lastOrNull { it.sourceId == sourceId } ?: run { importMessage.value = "找不到来源 $sourceId 对应的索引任务"; return@launch }
        db.workItems().updateState(item.id, "QUEUED", item.stage, item.progress, item.checkpoint, item.error, System.currentTimeMillis())
        app.workScheduler.scheduleAudit(item.id)
        importMessage.value = "已从 checkpoint 恢复索引：${item.checkpoint ?: "从头开始"}"
    }

    fun newConversation() = viewModelScope.launch { val now = System.currentTimeMillis(); val conv = ConversationEntity(UUID.randomUUID().toString(), "新对话", now, now); repository.createConversation(conv); _currentConversationId.value = conv.id }
    fun sendAgentMessage(text: String) = viewModelScope.launch {
        val convId = _currentConversationId.value ?: run { newConversation(); return@launch }; val now = System.currentTimeMillis()
        repository.addMessage(MessageEntity(UUID.randomUUID().toString(), convId, null, null, repository.nextMessageSequence(convId), "user", text, createdAt = now))
        repository.addMessage(MessageEntity(UUID.randomUUID().toString(), convId, null, null, repository.nextMessageSequence(convId), "assistant", "已收到：$text\n\n（AI Provider 尚未接入，当前为占位回复）", createdAt = System.currentTimeMillis()))
    }

    // ── 训练界面映射（画面帧 + 触点上报，hlpatch v3.28.0+）──
    private val _mirrorFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val mirrorFrame: StateFlow<android.graphics.Bitmap?> = _mirrorFrame.asStateFlow()
    private val _mirrorInfo = MutableStateFlow("未连接")
    val mirrorInfo: StateFlow<String> = _mirrorInfo.asStateFlow()
    private val _mirrorTouches = MutableStateFlow<List<String>>(emptyList())
    val mirrorTouches: StateFlow<List<String>> = _mirrorTouches.asStateFlow()
    val mirrorRunning = MutableStateFlow(false)

    fun mirrorPollOnce() = viewModelScope.launch {
        val r = hlpatchClient.fetchBytes("/api/frame")
        if (r == null) { _mirrorInfo.value = "无帧（hlpatch 未装画面 hook 或游戏未在渲染）"; return@launch }
        val (bytes, headers) = r
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp == null) { _mirrorInfo.value = "帧解码失败（${bytes.size}B）"; return@launch }
        _mirrorFrame.value = bmp
        val seq = headers["x-frame-seq"] ?: "?"
        val ts = headers["x-frame-ts"] ?: "?"
        val w = headers["x-frame-w"] ?: "?"
        val h = headers["x-frame-h"] ?: "?"
        _mirrorInfo.value = "帧#$seq · ${w}x${h} · ${bytes.size / 1024}KB · ts=$ts"
    }

    fun mirrorStart() { if (mirrorRunning.value) return; mirrorRunning.value = true; viewModelScope.launch { while (mirrorRunning.value) { mirrorPollOnce(); kotlinx.coroutines.delay(200) } } }
    fun mirrorStop() { mirrorRunning.value = false }
    fun mirrorToggleCollect(enabled: Boolean) = viewModelScope.launch { hlpatchClient.get("/api/frame_toggle?enabled=${if (enabled) 1 else 0}") }

    /** 归一化坐标 (0..1) 上报，B 阶段 hlpatch 注入游戏。 */
    fun mirrorTouch(nx: Double, ny: Double) = viewModelScope.launch {
        val body = "{\"x\":${"%.4f".format(nx)},\"y\":${"%.4f".format(ny)}}"
        val r = hlpatchClient.post("/api/touch", body)
        if (r.ok) {
            _mirrorTouches.value = (listOf("${"%.3f".format(nx)},${"%.3f".format(ny)}${if (r.body.contains("a_logged_only")) "（已记录·注入未实装）" else ""}") + _mirrorTouches.value).take(8)
        }
    }

    fun connectHlpatch() = viewModelScope.launch { hlpatchState.value = HlpatchClient.ConnectionState.CONNECTING; hlpatchClient.health(); hlpatchState.value = hlpatchClient.state }
    fun discoverHlpatchCapabilities() = viewModelScope.launch { hlpatchCapabilities.value = HlpatchCapabilityReport(running = true); hlpatchCapabilities.value = hlpatchClient.discoverCapabilities(); hlpatchState.value = hlpatchClient.state }

    fun dumpSid() = viewModelScope.launch {
        dumpState.value = "正在连接 hlpatch…"; val health = hlpatchClient.health()
        if (!health.ok) { dumpState.value = "hlpatch 未连接"; return@launch }
        dumpState.value = "正在读取 /summary…"; val status = hlpatchClient.status()
        if (!status.ok) { dumpState.value = "读取失败\n${status.body}"; return@launch }
        val sid = extractJsonField(status.body, "sid") ?: extractJsonField(status.body, "SID"); val vid = extractJsonField(status.body, "viewer_id") ?: extractJsonField(status.body, "viewerId")
        if (sid != null) {
            val headers = buildMap { put("APP-VER", extractJsonField(status.body, "app_ver") ?: "2.29.0"); extractJsonField(status.body, "res_ver")?.let { put("RES-VER", it) }; extractJsonField(status.body, "device_id")?.let { put("Device-Id", it) } }
            sessionManager.importFromHlpatch(sid, vid?.toLongOrNull() ?: 0L, headers); dumpState.value = "已 dump SID: $sid"
        } else dumpState.value = "未找到 SID；完整响应：\n${status.body}"
    }

    fun checkSidHealth(sid: String, viewerId: Long?) = viewModelScope.launch {
        val exactViewerId = viewerId ?: 0L; sidHealthState.value = SidHealthCheckState(running = true, checkedSid = sid, viewerId = viewerId); val active = activeSession.value
        val session = if (active != null && active.sid == sid && active.viewerId == exactViewerId) active else GameSession(sid, exactViewerId, null, null, active?.appVer ?: "2.29.0", active?.resVer, active?.resVerHash, active?.deviceId, active?.deviceName, active?.platformOsVersion, System.currentTimeMillis(), SessionSource.MANUAL_INPUT, exactViewerId > 0)
        runCatching { sidHealthProbe.probe(session, protocolSender::sendViaHlpatch) }.onSuccess { sidHealthState.value = SidHealthCheckState(checkedSid = sid, viewerId = viewerId, result = it) }.onFailure { sidHealthState.value = SidHealthCheckState(checkedSid = sid, viewerId = viewerId, error = it.stackTraceToString()) }
        protocolHistoryTimeline.reload()
    }

    fun sendProtocolRequest(endpoint: String, sid: String, viewerId: Long?, body: String, channel: Int) = viewModelScope.launch {
        val ep = GameEndpoint.fromPath(endpoint); val req = GameRequest(ep, sid.ifBlank { null }, viewerId, body, headers = sessionManager.buildHeaders(activeSession.value), rawEndpoint = endpoint)
        runCatching { if (channel == 2) protocolSender.sendViaHlpatch(req) else throw IllegalStateException("直连服务器地址尚未由真实配置提供；请求未发送，完整输入仍保留") }
        protocolHistoryTimeline.reload()
    }

    private fun extractJsonField(json: String, field: String): String? {
        val patterns = listOf("\"$field\"", "'$field'", "$field:")
        for (pattern in patterns) { val idx = json.indexOf(pattern, ignoreCase = true); if (idx >= 0) { val after = json.substring(idx + pattern.length).trimStart(' ', ':', '"', '\''); val end = after.indexOfFirst { it == '"' || it == '\'' || it == ',' || it == '}' || it == '\n' }; val value = if (end >= 0) after.substring(0, end) else after; if (value.isNotEmpty()) return value } }
        return null
    }
}
