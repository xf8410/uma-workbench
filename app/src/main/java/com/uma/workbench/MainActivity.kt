package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.data.WorkspaceEntity
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.network.NetworkState
import com.uma.workbench.ui.MainViewModel
import com.uma.workbench.ui.theme.WorkbenchColors
import com.uma.workbench.ui.theme.WorkbenchTheme
import com.uma.workbench.ui.viewers.CodeViewer
import com.uma.workbench.ui.viewers.JsonTreeView
import com.uma.workbench.ui.viewers.HexViewer
import com.uma.workbench.ui.viewers.TerminalLogViewer
import com.uma.workbench.ui.viewers.LogEntry
import com.uma.workbench.ui.viewers.LogLevel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WorkbenchTheme { WorkbenchApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchApp(vm: MainViewModel = viewModel()) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val currentWs by vm.currentWorkspace.collectAsStateWithLifecycle()
    val networkState by vm.networkState.collectAsStateWithLifecycle()
    val hlpatchState by vm.hlpatchState.collectAsStateWithLifecycle()

    if (currentWs == null) {
        WorkspacePicker(workspaces, vm)
    } else {
        TraeLayout(vm, currentWs!!, networkState, hlpatchState)
    }
}

// ── 工作区选择 ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePicker(workspaces: List<WorkspaceEntity>, vm: MainViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = WorkbenchColors.bg) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("UMA Workbench", style = MaterialTheme.typography.headlineMedium, color = WorkbenchColors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text("选择或创建工作区", color = WorkbenchColors.textSecondary)
            Spacer(Modifier.height(24.dp))

            if (workspaces.isEmpty()) {
                Text("暂无工作区", color = WorkbenchColors.textMuted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(workspaces, key = { it.id }) { ws ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(WorkbenchColors.bgSurface)
                                .clickable { vm.openWorkspace(ws.id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (ws.pinned) Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = WorkbenchColors.warning)
                            else Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = WorkbenchColors.accent)
                            Spacer(Modifier.width(8.dp))
                            Text(ws.name, color = WorkbenchColors.textPrimary)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Button(onClick = { showCreate = true }, colors = ButtonDefaults.buttonColors(containerColor = WorkbenchColors.accent)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("新建工作区")
            }
        }

        if (showCreate) {
            AlertDialog(
                onDismissRequest = { showCreate = false; newName = "" },
                title = { Text("新建工作区") },
                text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { vm.createWorkspace(newName); newName = ""; showCreate = false } }) { Text("创建") } },
                dismissButton = { TextButton(onClick = { showCreate = false; newName = "" }) { Text("取消") } }
            )
        }
    }
}

// ── Trae 式三栏布局 ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraeLayout(
    vm: MainViewModel,
    ws: WorkspaceEntity,
    networkState: NetworkState,
    hlpatchState: HlpatchClient.ConnectionState
) {
    var leftCollapsed by remember { mutableStateOf(false) }
    var rightCollapsed by remember { mutableStateOf(false) }
    var activeBottomTab by remember { mutableStateOf(0) } // 0=代码 1=历史 2=协议

    val projects by vm.projects.collectAsStateWithLifecycle()
    val recentFiles by vm.recentFiles.collectAsStateWithLifecycle()
    val openTabs by vm.openTabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val fileContent by vm.fileContent.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = WorkbenchColors.bg) {
        Column(Modifier.fillMaxSize()) {
            // 顶部状态栏
            TopBar(ws.name, networkState, hlpatchState, onBack = { vm.closeWorkspace() })

            Row(Modifier.weight(1f)) {
                // 左栏：项目树
                if (!leftCollapsed) {
                    SidePanel(
                        width = 220.dp,
                        onCollapse = { leftCollapsed = true }
                    ) {
                        LeftPanelContent(ws, projects, recentFiles, vm)
                    }
                } else {
                    IconButton(onClick = { leftCollapsed = false }, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.ChevronRight, null, tint = WorkbenchColors.textSecondary)
                    }
                }

                // 中栏：查看器 + 底部历史
                Column(Modifier.weight(1f)) {
                    // 文件标签栏
                    TabBar(openTabs, activeTabId, onSelect = { vm.selectTab(it) }, onClose = { vm.closeTab(it) })

                    // 主查看区
                    Box(Modifier.weight(1f).background(WorkbenchColors.bg)) {
                        val activeTab = openTabs.find { it.id == activeTabId }
                        if (activeTab == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("打开文件开始工作", color = WorkbenchColors.textMuted)
                            }
                        } else {
                            val content = fileContent ?: ""
                            val isJson = content.trimStart().startsWith("{") || content.trimStart().startsWith("[")
                            val isCode = activeTab.title.endsWith(".kt") || activeTab.title.endsWith(".xml") || activeTab.title.endsWith(".md")
                            val isHex = activeTab.title.endsWith(".so") || activeTab.title.endsWith(".dat")

                            when {
                                isJson -> JsonTreeView(content)
                                isCode -> CodeViewer(content, language = if (activeTab.title.endsWith(".kt")) "kotlin" else if (activeTab.title.endsWith(".xml")) "xml" else "text")
                                isHex -> HexViewer(content.toByteArray())
                                else -> CodeViewer(content, language = "text")
                            }
                        }
                    }

                    // 底部历史日志区
                    if (activeBottomTab == 1) {
                        Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.height(180.dp)) {
                            TerminalLogViewer(
                                entries = messages.map { msg ->
                                    LogEntry(
                                        timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(msg.createdAt)),
                                        level = if (msg.status == "COMPLETE") LogLevel.OK else LogLevel.INFO,
                                        source = msg.role,
                                        message = msg.content.take(200)
                                    )
                                }
                            )
                        }
                    }
                    // 底部协议面板
                    if (activeBottomTab == 2) {
                        ProtocolPanel(vm)
                    }
                }

                // 右栏：Agent
                if (!rightCollapsed) {
                    SidePanel(
                        width = 280.dp,
                        onCollapse = { rightCollapsed = true },
                        alignRight = true
                    ) {
                        AgentPanelContent(conversations, messages, vm, hlpatchState)
                    }
                } else {
                    IconButton(onClick = { rightCollapsed = false }, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.Default.ChevronLeft, null, tint = WorkbenchColors.textSecondary)
                    }
                }
            }

            // 底部状态栏
            BottomBar(activeBottomTab, onTabChange = { activeBottomTab = it })
        }
    }
}

@Composable
private fun TopBar(wsName: String, net: NetworkState, hlpatch: HlpatchClient.ConnectionState, onBack: () -> Unit) {
    Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Home, null, Modifier.size(16.dp), tint = WorkbenchColors.textSecondary)
            }
            Spacer(Modifier.width(4.dp))
            Text(wsName, color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            StatusBadge(
                when (hlpatch) {
                    HlpatchClient.ConnectionState.READY -> "hlpatch:READY" to WorkbenchColors.success
                    HlpatchClient.ConnectionState.CONNECTING -> "hlpatch:连接中" to WorkbenchColors.warning
                    HlpatchClient.ConnectionState.DEGRADED -> "hlpatch:降级" to WorkbenchColors.warning
                    HlpatchClient.ConnectionState.DISCONNECTED -> "hlpatch:断开" to WorkbenchColors.textMuted
                    else -> "hlpatch:?" to WorkbenchColors.textMuted
                }
            )
            Spacer(Modifier.width(4.dp))
            StatusBadge(
                when (net) {
                    NetworkState.ONLINE -> "在线" to WorkbenchColors.success
                    NetworkState.SWITCHING -> "切换中" to WorkbenchColors.warning
                    NetworkState.OFFLINE -> "离线" to WorkbenchColors.textMuted
                }
            )
        }
    }
}

@Composable
private fun StatusBadge(pair: Pair<String, Color>) {
    Surface(color = pair.second.copy(alpha = 0.15f), shape = RoundedCornerShape(3.dp)) {
        Text(pair.first, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = pair.second)
    }
}

@Composable
private fun SidePanel(width: androidx.compose.ui.unit.Dp, onCollapse: () -> Unit, alignRight: Boolean = false, content: @Composable () -> Unit) {
    Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.width(width).fillMaxHeight()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start) {
                IconButton(onClick = onCollapse, modifier = Modifier.size(24.dp)) {
                    Icon(if (alignRight) Icons.Default.ChevronRight else Icons.Default.ChevronLeft, null, Modifier.size(16.dp), tint = WorkbenchColors.textMuted)
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun LeftPanelContent(ws: WorkspaceEntity, projects: List<com.uma.workbench.data.ProjectEntity>, recentFiles: List<com.uma.workbench.data.RecentFileEntity>, vm: MainViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text("项目", style = MaterialTheme.typography.labelMedium, color = WorkbenchColors.textSecondary, modifier = Modifier.padding(vertical = 4.dp))
        projects.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { }.padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, null, Modifier.size(14.dp), tint = WorkbenchColors.accent)
                Spacer(Modifier.width(4.dp))
                Text(p.name, color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = { showAdd = true }, modifier = Modifier.padding(start = 0.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("添加项目", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(8.dp))
        Text("最近文件", style = MaterialTheme.typography.labelMedium, color = WorkbenchColors.textSecondary, modifier = Modifier.padding(vertical = 4.dp))
        recentFiles.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { vm.openFile(f.uri, f.name) }.padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Description, null, Modifier.size(14.dp), tint = WorkbenchColors.textMuted)
                Spacer(Modifier.width(4.dp))
                Text(f.name, color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.weight(1f))
        // 文件导入按钮
        TextButton(onClick = { vm.importFile() }) {
            Icon(Icons.Default.FileOpen, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("导入文件", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; newName = "" },
            title = { Text("添加项目") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("项目名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { vm.addProject(newName, null); newName = ""; showAdd = false } }) { Text("添加") } },
            dismissButton = { TextButton(onClick = { showAdd = false; newName = "" }) { Text("取消") } }
        )
    }
}

@Composable
private fun TabBar(tabs: List<com.uma.workbench.data.OpenTabEntity>, activeId: String?, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    if (tabs.isEmpty()) return
    val scrollState = rememberScrollState()
    Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.fillMaxWidth().height(32.dp)) {
        Row(Modifier.fillMaxSize().horizontalScroll(scrollState).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val isActive = tab.id == activeId
                Surface(
                    color = if (isActive) WorkbenchColors.bg else WorkbenchColors.bgSecondary,
                    modifier = Modifier.height(28.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).clickable { onSelect(tab.id) }
                ) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, Modifier.size(12.dp), tint = WorkbenchColors.textMuted)
                        Spacer(Modifier.width(4.dp))
                        Text(tab.title, style = MaterialTheme.typography.labelSmall, color = if (isActive) WorkbenchColors.textPrimary else WorkbenchColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(16.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = WorkbenchColors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPanelContent(conversations: List<com.uma.workbench.data.ConversationEntity>, messages: List<com.uma.workbench.data.MessageEntity>, vm: MainViewModel, hlpatchState: HlpatchClient.ConnectionState) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        // 上下文引用区
        Surface(color = WorkbenchColors.accentDim.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, null, Modifier.size(12.dp), tint = WorkbenchColors.accent)
                Spacer(Modifier.width(4.dp))
                Text("当前文件上下文", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.accent)
            }
        }

        // 消息列表
        LazyColumn(Modifier.weight(1f).padding(horizontal = 4.dp), state = listState) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (messages.isEmpty()) {
                item {
                    Text("发送消息开始对话", color = WorkbenchColors.textMuted, modifier = Modifier.padding(8.dp))
                }
            }
        }

        // Thought 区（AI思考过程预览）
        if (messages.any { it.role == "assistant" && it.status != "COMPLETE" }) {
            Surface(color = WorkbenchColors.bgSurface, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text("Thinking…", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted, modifier = Modifier.padding(6.dp))
            }
        }

        // hlpatch 连接按钮
        if (hlpatchState == HlpatchClient.ConnectionState.DISCONNECTED) {
            TextButton(onClick = { vm.connectHlpatch() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Default.Cable, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("连接 hlpatch", style = MaterialTheme.typography.labelSmall)
            }
        }

        // 输入区
        Surface(color = WorkbenchColors.bgSurface, modifier = Modifier.fillMaxWidth().padding(4.dp), shape = RoundedCornerShape(6.dp)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发送消息…", style = MaterialTheme.typography.bodySmall) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = WorkbenchColors.textPrimary, unfocusedTextColor = WorkbenchColors.textPrimary,
                        cursorColor = WorkbenchColors.accent
                    ),
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = 4
                )
                IconButton(onClick = { if (input.isNotBlank()) { vm.sendAgentMessage(input); input = "" } }) {
                    Icon(Icons.Default.Send, null, tint = WorkbenchColors.accent)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: com.uma.workbench.data.MessageEntity) {
    val isUser = msg.role == "user"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser) {
            Text("Assistant", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.accent, modifier = Modifier.padding(start = 4.dp))
        }
        Surface(
            color = if (isUser) WorkbenchColors.accentDim else WorkbenchColors.bgSurface,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 2.dp)
        ) {
            Text(
                msg.content,
                Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = WorkbenchColors.textPrimary
            )
        }
    }
}

@Composable
private fun BottomBar(activeTab: Int, onTabChange: (Int) -> Unit) {
    Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.fillMaxWidth().height(24.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BottomTab("代码", activeTab == 0) { onTabChange(0) }
            BottomTab("历史", activeTab == 1) { onTabChange(1) }
            BottomTab("协议", activeTab == 2) { onTabChange(2) }
            Spacer(Modifier.weight(1f))
            Text("UMA Workbench v0.1", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted)
        }
    }
}

@Composable
private fun BottomTab(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(color = if (active) WorkbenchColors.accentDim else Color.Transparent, modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onClick)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (active) WorkbenchColors.accent else WorkbenchColors.textMuted)
    }
}

// ── 协议面板 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolPanel(vm: MainViewModel) {
    var selectedEndpoint by remember { mutableStateOf("login") }
    var sidInput by remember { mutableStateOf("") }
    var viewerIdInput by remember { mutableStateOf("") }
    var bodyInput by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableStateOf(0) }
    
    val protoLogs by vm.protocolLogs.collectAsStateWithLifecycle()
    val activeSession by vm.activeSession.collectAsStateWithLifecycle()
    val dumpState by vm.dumpState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().height(280.dp).background(WorkbenchColors.bg)) {
        // 顶部：SID dump 区
        Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SID", style = MaterialTheme.typography.labelMedium, color = WorkbenchColors.accent, modifier = Modifier.width(30.dp))
                OutlinedTextField(
                    value = sidInput, onValueChange = { sidInput = it },
                    placeholder = { Text("SID", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f).height(36.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    colors = textFieldColors()
                )
                Spacer(Modifier.width(8.dp))
                Text("VID", style = MaterialTheme.typography.labelMedium, color = WorkbenchColors.accent, modifier = Modifier.width(30.dp))
                OutlinedTextField(
                    value = viewerIdInput, onValueChange = { viewerIdInput = it },
                    placeholder = { Text("viewer_id", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.width(100.dp).height(36.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    colors = textFieldColors()
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.dumpSid() },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WorkbenchColors.accentDim, contentColor = WorkbenchColors.accentBright),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                ) {
                    Text("Dump", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(4.dp))
                if (activeSession != null) {
                    val s = activeSession!!
                    Surface(color = if (s.bound) WorkbenchColors.success.copy(alpha = 0.2f) else WorkbenchColors.warning.copy(alpha = 0.2f), shape = RoundedCornerShape(3.dp)) {
                        Text(
                            if (s.bound) "SID ${s.sid.take(8)}… 已绑定 ${s.viewerId}" else "SID ${s.sid.take(8)}… 未绑定",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.bound) WorkbenchColors.success else WorkbenchColors.warning
                        )
                    }
                }
                if (dumpState.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(dumpState, style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted)
                }
            }
        }
        // 中部：端点选择 + 请求体 + 通道 + 发送
        Surface(color = WorkbenchColors.bg, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val endpoints = listOf("login", "start_session", "load/index", "boot", "pre_signup", "signup")
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selectedEndpoint, onValueChange = {}, readOnly = true,
                            label = { Text("端点", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.menuAnchor().width(160.dp).height(36.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = textFieldColors()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            endpoints.forEach { ep ->
                                DropdownMenuItem(text = { Text(ep, fontFamily = FontFamily.Monospace) }, onClick = { selectedEndpoint = ep; expanded = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val channels = listOf("直发", "自定义TLS", "hlpatch转发")
                    channels.forEachIndexed { idx, label ->
                        Surface(
                            color = if (selectedChannel == idx) WorkbenchColors.accentDim else Color.Transparent,
                            shape = RoundedCornerShape(3.dp),
                            modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable { selectedChannel = idx }
                        ) {
                            Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (selectedChannel == idx) WorkbenchColors.accent else WorkbenchColors.textMuted)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            vm.sendProtocolRequest(selectedEndpoint, sidInput, viewerIdInput.toLongOrNull(), bodyInput, selectedChannel)
                        },
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WorkbenchColors.accent, contentColor = Color.White),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("发送", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = bodyInput, onValueChange = { bodyInput = it },
                    placeholder = { Text("请求体（JSON/明文）", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = WorkbenchColors.textPrimary),
                    colors = textFieldColors()
                )
            }
        }
        // 底部：协议日志
        Surface(color = WorkbenchColors.bgSecondary, modifier = Modifier.height(80.dp)) {
            if (protoLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无协议日志", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted)
                }
            } else {
                TerminalLogViewer(
                    entries = protoLogs.map { entry ->
                        LogEntry(
                            timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(entry.timestamp)),
                            level = if (entry.response?.success == true) LogLevel.OK else LogLevel.ERR,
                            source = entry.channel.name,
                            message = "${entry.request.endpoint.path}: ${entry.response?.protocolCode?.label ?: entry.error ?: "无响应"}"
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = WorkbenchColors.textPrimary,
    unfocusedTextColor = WorkbenchColors.textPrimary,
    focusedContainerColor = WorkbenchColors.bgSurface,
    unfocusedContainerColor = WorkbenchColors.bgSurface,
    focusedBorderColor = WorkbenchColors.accent,
    unfocusedBorderColor = WorkbenchColors.border,
    cursorColor = WorkbenchColors.accent,
    focusedLabelColor = WorkbenchColors.accent,
    unfocusedLabelColor = WorkbenchColors.textMuted
)
