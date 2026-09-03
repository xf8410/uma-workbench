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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.roundToInt

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

/** 功能页签：emoji 图标 + 名称，顺序即索引（与 when 分支一一对应）。 */
private val WorkbenchTabs = listOf(
    "📄" to "代码", "🕘" to "历史", "📡" to "协议", "📥" to "导入索引",
    "💬" to "AI 聊天", "⚙️" to "AI 配置", "👥" to "伙伴与群聊", "🔍" to "确定性审计",
    "🐙" to "GitHub", "📚" to "知识库", "🔤" to "LSP", "🧩" to "插件", "🎮" to "训练映射"
)

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
    // 2026-09-03 用户反馈：功能栏别压底部，改左侧边栏；选中页签后自动收起，手指从左边划出可再显示。
    // 实现：覆盖式抽屉（不占内容宽度，天然解决上一版 220dp 固定栏挤压对话区的问题）——
    //  ☰ 按钮 / 屏幕左缘 32dp 内向右划 / 左缘握把条 三种方式打开；
    //  点页签或点遮罩关闭；面板上向左划也可关闭。
    var activeTab by rememberSaveable { mutableIntStateOf(0) }
    var drawerOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val drawerWidth = 252.dp
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    val edgeThresholdPx = with(density) { 32.dp.toPx() }
    val slide by animateFloatAsState(targetValue = if (drawerOpen) 1f else 0f, animationSpec = tween(220), label = "drawerSlide")
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
    Surface(
        Modifier.fillMaxSize().pointerInput(drawerOpen) {
            // 左缘向右划 = 打开抽屉；仅当起点贴左缘且横向位移足够时触发，
            // 其余横向手势留给子级（hex 横滚/标签横滚）优先消费
            var startX = 0f
            var accumulated = 0f
            detectHorizontalDragGestures(
                onDragStart = { offset -> startX = offset.x; accumulated = 0f },
                onDragEnd = {
                    if (!drawerOpen && startX < edgeThresholdPx && accumulated > edgeThresholdPx) drawerOpen = true
                },
                onDragCancel = { }
            ) { _, dragAmount -> accumulated += dragAmount }
        },
        color = WorkbenchColors.bg
    ) {
        // Surface 内容是 ColumnScope——遮罩/面板必须放进 Box 才能覆盖内容而不是排在下方
        Box(Modifier.fillMaxSize()) {
            Column {
                // Agora 风格悬浮胶囊顶栏：safeDrawing 已避开状态栏/刘海，额外下沉 16dp 留白
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(WorkbenchColors.bgSurface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ drawerOpen = !drawerOpen }, Modifier.size(32.dp)) { Icon(Icons.Default.Menu, "功能栏", tint = WorkbenchColors.textSecondary) }
                    IconButton({ vm.closeWorkspace() }, Modifier.size(32.dp)) { Icon(Icons.Default.Home, null, tint = WorkbenchColors.textSecondary) }
                    Text(ws.name, color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    Text("hlpatch:${hlpatchState.name} · ${networkState.name}", color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // 收起状态下的左缘握把：一条细竖带 + 居中圆点，点按或向右拖都打开
                    if (slide < 0.02f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(12.dp)
                                .pointerInput(Unit) {
                                    var accumulated = 0f
                                    detectHorizontalDragGestures(
                                        onDragStart = { accumulated = 0f },
                                        onDragEnd = { if (accumulated > 24.dp.toPx()) drawerOpen = true }
                                    ) { _, dragAmount -> accumulated += dragAmount }
                                }
                                .pointerInput(Unit) { detectTapGestures { drawerOpen = true } }
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 3.dp)
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(WorkbenchColors.bgSecondary)
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        when (activeTab) {
                            4 -> AiChatScreen(aiChatVm) { activeTab = 5 }
                            5 -> AiConfigurationScreen(aiConfigVm, lanModelVm, localModelVm)
                            6 -> AgentPartnerPanel(agentPartnerVm, ws.id) { activeTab = 0 }
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
                                when (activeTab) {
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
            }
            // 抽屉遮罩：半透明变暗 + 点按关闭（slide>0 才参与组合，关闭动画结束后彻底消失）
            if (slide > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f * slide))
                        .pointerInput(Unit) { detectTapGestures { drawerOpen = false } }
                )
                // 抽屉面板：功能页签 + 项目/最近文件/导入并索引；选中页签即切换并收起
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .offset { IntOffset(((slide - 1f) * drawerWidthPx).roundToInt(), 0) }
                        .background(WorkbenchColors.bgSecondary)
                        .pointerInput(Unit) {
                            var accumulated = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { accumulated = 0f },
                                onDragEnd = { if (accumulated < -32.dp.toPx()) drawerOpen = false }
                            ) { _, dragAmount -> accumulated += dragAmount }
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp)
                ) {
                    Text("功能", color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                    WorkbenchTabs.forEachIndexed { index, (icon, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (index == activeTab) WorkbenchColors.bgSurface else Color.Transparent)
                                .clickable {
                                    activeTab = index
                                    drawerOpen = false
                                    if (index == 4) aiChatVm.refreshConfiguration()
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(icon, fontSize = 16.sp)
                            Text(label, color = if (index == activeTab) WorkbenchColors.accent else WorkbenchColors.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("项目", color = WorkbenchColors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp))
                    projects.forEach { Text(it.name, color = WorkbenchColors.textPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    Text("最近文件", color = WorkbenchColors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    recentFiles.forEach { file ->
                        Text(
                            file.name,
                            color = WorkbenchColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { vm.openFile(file.uri, file.name); drawerOpen = false }.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    TextButton(
                        onClick = { importLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/x-tar", "application/octet-stream", "application/json", "text/plain", "*/*")) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, null, Modifier.size(16.dp))
                        Text("导入并索引", modifier = Modifier.padding(start = 6.dp))
                    }
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
