package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.data.WorkspaceEntity
import com.uma.workbench.hlpatch.HlpatchClient
import com.uma.workbench.network.NetworkState
import com.uma.workbench.ui.MainViewModel
import com.uma.workbench.ui.panels.AgentPanel
import com.uma.workbench.ui.panels.ProjectTreePanel
import com.uma.workbench.ui.panels.ViewerPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WorkbenchTheme { WorkbenchApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WorkbenchApp(vm: MainViewModel = viewModel()) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WorkspacePicker(workspaces: List<WorkspaceEntity>, vm: MainViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("UMA Workbench") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("工作区", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("选择或创建工作区开始工作", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(workspaces, key = { it.id }) { ws ->
                    Card(onClick = { vm.openWorkspace(ws.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ws.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("创建于 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ws.createdAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (ws.pinned) Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (showCreate) {
                AlertDialog(
                    onDismissRequest = { showCreate = false; newName = "" },
                    title = { Text("新建工作区") },
                    text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("工作区名称") }, singleLine = true) },
                    confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { vm.createWorkspace(newName); newName = ""; showCreate = false } }) { Text("创建") } },
                    dismissButton = { TextButton(onClick = { showCreate = false; newName = "" }) { Text("取消") } }
                )
            }

            FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, contentDescription = "新建工作区") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TraeLayout(vm: MainViewModel, ws: WorkspaceEntity, networkState: NetworkState, hlpatchState: HlpatchClient.ConnectionState) {
    var leftCollapsed by remember { mutableStateOf(false) }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val recentFiles by vm.recentFiles.collectAsStateWithLifecycle()
    val openTabs by vm.openTabs.collectAsStateWithLifecycle()
    val activeTabId by vm.activeTabId.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize()) {
        // 左栏：项目树
        if (!leftCollapsed) {
            ProjectTreePanel(
                workspace = ws,
                projects = projects,
                recentFiles = recentFiles,
                onOpenFile = { uri, name -> vm.openFile(uri, name) },
                onAddProject = { name, uri -> vm.addProject(name, uri) },
                onBack = { vm.closeWorkspace() },
                modifier = Modifier.width(280.dp).fillMaxHeight()
            )
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 顶栏
            Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { leftCollapsed = !leftCollapsed }) { Icon(if (leftCollapsed) Icons.Default.Menu else Icons.Default.ChevronLeft, contentDescription = "切换左栏") }
                    Text(ws.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    HlpatchStatusBadge(hlpatchState)
                    Spacer(Modifier.width(8.dp))
                    NetworkBadge(networkState)
                }
            }

            // 中栏：查看器
            ViewerPanel(
                tabs = openTabs,
                activeTabId = activeTabId,
                onSelectTab = { vm.selectTab(it) },
                onCloseTab = { vm.closeTab(it) },
                vm = vm,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        }

        // 右栏：Agent
        AgentPanel(
            conversations = conversations,
            messages = messages,
            onSendMessage = { msg -> vm.sendAgentMessage(msg) },
            onNewConversation = { vm.newConversation() },
            hlpatchState = hlpatchState,
            onHlpatchConnect = { vm.connectHlpatch() },
            modifier = Modifier.width(320.dp).fillMaxHeight()
        )
    }
}

@Composable private fun HlpatchStatusBadge(state: HlpatchClient.ConnectionState) {
    val (color, text) = when (state) {
        HlpatchClient.ConnectionState.READY -> MaterialTheme.colorScheme.primary to "hlpatch 就绪"
        HlpatchClient.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary to "连接中…"
        HlpatchClient.ConnectionState.DEGRADED -> MaterialTheme.colorScheme.error to "hlpatch 降级"
        HlpatchClient.ConnectionState.OVERLOADED -> MaterialTheme.colorScheme.error to "hlpatch 过载"
        HlpatchClient.ConnectionState.INCOMPATIBLE -> MaterialTheme.colorScheme.error to "hlpatch 不兼容"
        HlpatchClient.ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline to "hlpatch 未连接"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
            Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable private fun NetworkBadge(state: NetworkState) {
    val (color, text) = when (state) {
        NetworkState.ONLINE -> MaterialTheme.colorScheme.primary to "在线"
        NetworkState.SWITCHING -> MaterialTheme.colorScheme.tertiary to "切换中…"
        NetworkState.OFFLINE -> MaterialTheme.colorScheme.outline to "离线"
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable fun WorkbenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
