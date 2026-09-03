package com.uma.workbench

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.agent.ActiveWorkspaceDocument
import com.uma.workbench.agent.ActiveWorkspaceDocumentBridge
import com.uma.workbench.agent.AgentPartnerViewModel
import com.uma.workbench.knowledge.KnowledgePanel
import com.uma.workbench.lsp.LspPanel
import com.uma.workbench.ui.PluginPanel
import com.uma.workbench.data.WorkspaceEntity
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.network.NetworkState
import com.uma.workbench.protocol.GameEndpoint
import com.uma.workbench.protocol.ProtocolEditorDefaultsFactory
import com.uma.workbench.ui.*
import com.uma.workbench.ui.theme.WorkbenchColors
import com.uma.workbench.ui.theme.WorkbenchTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+（targetSdk 35 起，含 Android 16 / ColorOS 16）强制 Edge-to-Edge：
        // statusBarColor / navigationBarColor 失效，内容会绘制到系统栏后面。
        // 显式启用并固定深色系统栏（浅色图标），与应用深色主题保持一致。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // Android 13+ 通知是运行时权限：不申请则桌宠前台通知被系统静默吞掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            WorkbenchTheme {
                // safeDrawing 避让状态栏/手势条/刘海/键盘：
                // 修复 Edge-to-Edge 强制后内容顶进系统栏的问题
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    WorkbenchApp()
                }
            }
        }
    }
}

@Composable
fun WorkbenchApp(
    vm: MainViewModel = viewModel(),
    aiConfigVm: AiConfigurationViewModel = viewModel(),
    lanModelVm: LanModelViewModel = viewModel(),
    aiChatVm: AiChatViewModel = viewModel(),
    agentPartnerVm: AgentPartnerViewModel = viewModel(),
    auditVm: DeterministicAuditViewModel = viewModel(),
    githubVm: GitHubViewModel = viewModel(),
    localModelVm: LocalSmallModelViewModel = viewModel(),
    knowledgeVm: com.uma.workbench.knowledge.KnowledgeViewModel = viewModel(),
    lspVm: com.uma.workbench.lsp.LspViewModel = viewModel()
) {
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val pluginRepository = remember { com.uma.workbench.plugin.PluginRegistryRepository(com.uma.workbench.plugin.PluginRegistryDatabase.get(appContext).plugins()) }
    val appVersionCode = remember { runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode }.getOrDefault(1) }
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val currentWs by vm.currentWorkspace.collectAsStateWithLifecycle()
    val networkState by vm.networkState.collectAsStateWithLifecycle()
    val hlpatchState by vm.hlpatchState.collectAsStateWithLifecycle()
    if (currentWs == null) WorkspacePicker(workspaces, vm)
    else TraeLayout(vm, aiConfigVm, lanModelVm, localModelVm, aiChatVm, agentPartnerVm, auditVm, githubVm, knowledgeVm, lspVm, pluginRepository, appVersionCode, currentWs!!, networkState, hlpatchState)
}

@Composable
private fun WorkspacePicker(workspaces: List<WorkspaceEntity>, vm: MainViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = WorkbenchColors.bg) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("UMA Workbench", style = MaterialTheme.typography.headlineMedium, color = WorkbenchColors.textPrimary)
            Text("选择或创建工作区", color = WorkbenchColors.textSecondary)
            Spacer(Modifier.height(24.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(workspaces, key = { it.id }) { ws ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(WorkbenchColors.bgSurface)
                            .clickable { vm.openWorkspace(ws.id) }.padding(12.dp)
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = WorkbenchColors.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(ws.name, color = WorkbenchColors.textPrimary)
                    }
                }
            }
            Button(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, null)
                Text("新建工作区")
            }
        }
        if (showCreate) AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建工作区") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("名称") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        vm.createWorkspace(newName)
                        showCreate = false
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TraeLayout(
    vm: MainViewModel,
    aiConfigVm: AiConfigurationViewModel,
    lanModelVm: LanModelViewModel,
    localModelVm: LocalSmallModelViewModel,
    aiChatVm: AiChatViewModel,
    agentPartnerVm: AgentPartnerViewModel,
    auditVm: DeterministicAuditViewModel,
    githubVm: GitHubViewModel,
    knowledgeVm: com.uma.workbench.knowledge.KnowledgeViewModel,
    lspVm: com.uma.workbench.lsp.LspViewModel,
    pluginRepository: com.uma.workbench.plugin.PluginRegistryRepository,
    appVersionCode: Int,
    ws: WorkspaceEntity,
    networkState: NetworkState,
    hlpatchState: HlpatchClient.ConnectionState
) {
    var activeBottomTab by remember { mutableIntStateOf(0) }
    // 左栏宽度可拖拽调节（2026-09-03 用户反馈：中间竖线应能拉伸两边的框，否则对话时看不到）：
    // 默认 220dp，范围 120..420dp，双击分隔条复位；rememberSaveable 旋转/重建后保留
    var sidebarWidth by rememberSaveable { mutableStateOf(220f) }
    var dividerDragging by remember { mutableStateOf(false) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val recentFiles by vm.recentFiles.collectAsStateWithLifecycle()
    val openTabs by vm.openTabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val importRows by vm.importRows.collectAsStateWithLifecycle()
    val importMessage by vm.importMessage.collectAsStateWithLifecycle()
    val activeDocument = openTabs.firstOrNull { it.id == activeTabId }
    LaunchedEffect(ws.id, activeDocument?.uri, activeDocument?.title) {
        ActiveWorkspaceDocumentBridge.publish(activeDocument?.let { ActiveWorkspaceDocument(ws.id, it.uri, it.title) })
    }
    DisposableEffect(Unit) { onDispose { ActiveWorkspaceDocumentBridge.publish(null) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importAndIndex(uris)
    }
    Surface(Modifier.fillMaxSize(), color = WorkbenchColors.bg) {
        Column {
            // Agora 风格悬浮胶囊顶栏（2026-09-02 用户反馈：顶栏太贴屏幕顶部、最上面的文字有时看不到）：
            // safeDrawing 已避开状态栏/刘海，这里再额外下沉 16dp 留白 + 圆角胶囊造型，顶部内容明显下降、清晰可读
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(WorkbenchColors.bgSurface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.closeWorkspace() }, Modifier.size(32.dp)) { Icon(Icons.Default.Home, null, tint = WorkbenchColors.textSecondary) }
                Text(ws.name, color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                Text("hlpatch:${hlpatchState.name} · ${networkState.name}", color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(sidebarWidth.dp).fillMaxHeight().background(WorkbenchColors.bgSecondary).padding(8.dp)) {
                    Text("项目", color = WorkbenchColors.textSecondary)
                    projects.forEach { Text(it.name, color = WorkbenchColors.textPrimary, modifier = Modifier.padding(4.dp)) }
                    Text("最近文件", color = WorkbenchColors.textSecondary, modifier = Modifier.padding(top = 8.dp))
                    recentFiles.forEach { file ->
                        Text(file.name, color = WorkbenchColors.textPrimary, modifier = Modifier.clickable { vm.openFile(file.uri, file.name) }.padding(4.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/x-tar", "application/octet-stream", "application/json", "text/plain", "*/*")) }) {
                        Icon(Icons.Default.FileOpen, null)
                        Text("导入并索引")
                    }
                }
                // 可拖动分隔条：14dp 宽触控区（手指好按），中间 2dp 视觉线；拖动实时改左栏宽度，双击复位 220dp
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(14.dp)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { dividerDragging = true },
                                onDragEnd = { dividerDragging = false },
                                onDragCancel = { dividerDragging = false }
                            ) { _, dragAmount ->
                                sidebarWidth = (sidebarWidth + dragAmount.toDp().value).coerceIn(120f, 420f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { sidebarWidth = 220f })
                        }
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .width(if (dividerDragging) 3.dp else 2.dp)
                            .background(if (dividerDragging) WorkbenchColors.accent else WorkbenchColors.bgSecondary)
                    )
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    when (activeBottomTab) {
                        4 -> AiChatScreen(aiChatVm) { activeBottomTab = 5 }
                        5 -> AiConfigurationScreen(aiConfigVm, lanModelVm, localModelVm)
                        6 -> AgentPartnerPanel(agentPartnerVm, ws.id) { activeBottomTab = 0 }
                        7 -> DeterministicAuditPanel(auditVm, ws.id)
                        8 -> GitHubEntryScreen(githubVm)
                        9 -> KnowledgePanel(knowledgeVm, ws.id)
                        10 -> LspPanel(lspVm, ws.id)
                        11 -> PluginPanel(pluginRepository, appVersionCode)
                        12 -> TrainingMirrorPanel(vm)
                        else -> {
                            if (openTabs.isNotEmpty()) Row(Modifier.fillMaxWidth().height(32.dp).horizontalScroll(rememberScrollState())) {
                                openTabs.forEach { tab ->
                                    Text(tab.title, color = if (tab.id == activeTabId) WorkbenchColors.accent else WorkbenchColors.textSecondary, modifier = Modifier.clickable { vm.selectTab(tab.id) }.padding(8.dp))
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) { ActiveDocumentPane(openTabs, activeTabId) }
                            when (activeBottomTab) {
                                1 -> ProtocolHistoryPanel(vm)
                                2 -> ProtocolPanel(vm)
                                3 -> Column(Modifier.height(430.dp)) {
                                    if (importMessage.isNotBlank()) Text(importMessage, color = WorkbenchColors.textMuted, modifier = Modifier.padding(8.dp))
                                    LazyColumn { item { ImportIndexPanel(importRows, vm::retryImport) } }
                                }
                            }
                        }
                    }
                }
            }
            // 底部标签栏同风格悬浮胶囊：28dp→44dp 加大触控面积，底部留白不贴手势条
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(WorkbenchColors.bgSurface).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf("代码", "历史", "协议", "导入索引", "AI 聊天", "AI 配置", "伙伴与群聊", "确定性审计", "GitHub", "知识库", "LSP", "插件", "训练映射").forEachIndexed { index, label ->
                    Text(label, color = if (index == activeBottomTab) WorkbenchColors.accent else WorkbenchColors.textMuted, modifier = Modifier.clickable {
                        activeBottomTab = index
                        if (index == 4) aiChatVm.refreshConfiguration()
                    }.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolPanel(vm: MainViewModel) {
    var selectedEndpoint by remember { mutableStateOf(GameEndpoint.LOGIN.path) }
    var sidInput by remember { mutableStateOf("") }
    var viewerIdInput by remember { mutableStateOf("") }
    var bodyInput by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableIntStateOf(2) }
    var endpointMenu by remember { mutableStateOf(false) }
    val activeSession by vm.activeSession.collectAsStateWithLifecycle()
    val logs by vm.protocolLogs.collectAsStateWithLifecycle()
    val dumpState by vm.dumpState.collectAsStateWithLifecycle()
    val healthState by vm.sidHealthState.collectAsStateWithLifecycle()
    val capabilities by vm.hlpatchCapabilities.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(selectedEndpoint, activeSession) {
        val defaults = ProtocolEditorDefaultsFactory.create(selectedEndpoint, activeSession, sidInput, viewerIdInput)
        sidInput = defaults.sid
        viewerIdInput = defaults.viewerId
        bodyInput = defaults.body
    }
    Column(Modifier.fillMaxWidth().height(430.dp).background(WorkbenchColors.bg).padding(8.dp)) {
        if (activeSession != null) {
            Text("活动 SID（完整）", color = WorkbenchColors.accent, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activeSession!!.sid, color = WorkbenchColors.textPrimary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()))
                TextButton(onClick = { clipboard.setText(AnnotatedString(activeSession!!.sid)) }) { Text("复制完整 SID") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(sidInput, { sidInput = it }, label = { Text("SID（完整）") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(viewerIdInput, { viewerIdInput = it }, label = { Text("viewer_id") }, singleLine = true, modifier = Modifier.width(140.dp))
            Button(onClick = { vm.dumpSid() }) { Text("Dump") }
        }
        if (dumpState.isNotBlank()) Text(dumpState, color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall)
        HlpatchCapabilityPanel(capabilities) { vm.discoverHlpatchCapabilities() }
        SidHealthPanel(healthState) { vm.checkSidHealth(sidInput, viewerIdInput.toLongOrNull()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(endpointMenu, { endpointMenu = it }) {
                OutlinedTextField(selectedEndpoint, {}, readOnly = true, label = { Text("端点") }, modifier = Modifier.menuAnchor().width(190.dp))
                ExposedDropdownMenu(endpointMenu, { endpointMenu = false }) {
                    GameEndpoint.entries.forEach { endpoint ->
                        DropdownMenuItem({ Text(endpoint.path) }, { selectedEndpoint = endpoint.path; endpointMenu = false })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            listOf("直发", "自定义TLS", "hlpatch转发").forEachIndexed { index, label ->
                Text(label, color = if (selectedChannel == index) WorkbenchColors.accent else WorkbenchColors.textMuted, modifier = Modifier.clickable { selectedChannel = index }.padding(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.sendProtocolRequest(selectedEndpoint, sidInput, viewerIdInput.toLongOrNull(), bodyInput, selectedChannel) }) { Text("发送") }
        }
        OutlinedTextField(bodyInput, { bodyInput = it }, label = { Text("可编辑请求体模板") }, modifier = Modifier.fillMaxWidth().weight(1f), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Text(if (logs.isEmpty()) "暂无协议日志" else logs.last().let { "${it.request.endpoint.path}: ${it.response?.protocolCode?.label ?: it.error ?: "无响应"}" }, color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Clip)
    }
}
