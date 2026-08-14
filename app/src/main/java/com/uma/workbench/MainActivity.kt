package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.network.NetworkState
import com.uma.workbench.ui.MainViewModel

private enum class Tab(val title: String) { Chat("对话"), Audit("审计"), History("历史"), GitHub("GitHub"), Settings("设置") }
class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { WorkbenchApp() } } } }

@Composable private fun WorkbenchApp(vm: MainViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.Chat) }; val network by vm.network.collectAsStateWithLifecycle()
    Scaffold(bottomBar = { NavigationBar { Tab.entries.forEach { item ->
        val icon = when (item) { Tab.Chat -> Icons.Default.Chat; Tab.Audit -> Icons.Default.Folder; Tab.History -> Icons.Default.History; Tab.GitHub -> Icons.Default.Cloud; Tab.Settings -> Icons.Default.Settings }
        NavigationBarItem(tab == item, { tab = item }, { Icon(icon, item.title) }, label = { Text(item.title) })
    } } }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) { NetworkBanner(network); Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { Text("UMA Workbench", style = MaterialTheme.typography.headlineSmall); when (tab) { Tab.Chat -> ChatPanel(vm); Tab.Audit -> AuditPanel(vm); Tab.History -> HistoryPanel(vm); Tab.GitHub -> GitHubPanel(); Tab.Settings -> SettingsPanel() } } } }
}
@Composable private fun NetworkBanner(state: NetworkState) { if (state == NetworkState.ONLINE) return; Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) { Text(if (state == NetworkState.OFFLINE) "当前离线：消息和任务已保存在本地，联网后继续" else "网络切换中：当前进度已保存", Modifier.padding(8.dp)) } }

@Composable private fun ChatPanel(vm: MainViewModel) {
    var input by remember { mutableStateOf("") }; val conversations by vm.conversations.collectAsStateWithLifecycle(); val selected by vm.selectedConversationId.collectAsStateWithLifecycle(); val messages by vm.messages.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("对话 ${conversations.size}", style = MaterialTheme.typography.titleLarge); Button(vm::createConversation) { Text("新建") } }
        if (conversations.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { conversations.take(4).forEach { item -> FilterChip(selected == item.id, { vm.selectConversation(item.id) }, { Text(item.title) }) } }
        Card(Modifier.fillMaxWidth()) { Text("项目规则和相关记忆由应用强制加载；加载失败时必须明确提示。", Modifier.padding(12.dp)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (messages.isEmpty()) item { Text("暂无消息。断网时发送的内容会进入本地待同步队列。") }; items(messages, key = { it.id }) { message -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(if (message.role == "USER") "你" else "助手", style = MaterialTheme.typography.labelMedium); Text(message.content); if (message.status != "COMPLETE") Text(message.status, style = MaterialTheme.typography.labelSmall) } } } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("输入消息") }); Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("发送") } }
    }
}

@Composable private fun AuditPanel(vm: MainViewModel) {
    val sources by vm.sources.collectAsStateWithLifecycle(); val status by vm.importStatus.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importDocuments)
    Column(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("项目审计", style = MaterialTheme.typography.titleLarge); Button({ picker.launch(arrayOf("application/octet-stream", "application/zip", "application/x-sqlite3", "*/*")) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("导入文件") } }
        Text("支持 SO、IL2CPP metadata、SQLite、Master、归档和会话文件；导入时分块计算 SHA-256。")
        status?.let { AssistChip({}, { Text(it) }) }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sources.isEmpty()) item { Text("尚未导入审计来源") }
            items(sources, key = { it.id }) { source -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(source.name, style = MaterialTheme.typography.titleMedium); Text("${source.kind} · ${source.sha256?.take(16) ?: "待计算"}"); source.duplicateOf?.let { Text("疑似重复来源：$it", color = MaterialTheme.colorScheme.tertiary) } } } }
        }
    }
}
@Composable private fun HistoryPanel(vm: MainViewModel) { val tasks by vm.workItems.collectAsStateWithLifecycle(); LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("任务与工具日志", style = MaterialTheme.typography.titleLarge) }; if (tasks.isEmpty()) item { Text("暂无任务") }; items(tasks, key = { it.id }) { Card(Modifier.fillMaxWidth()) { Text("${it.kind} · ${it.stage}\n${it.status} · ${it.progress}%", Modifier.padding(16.dp)) } } } }
@Composable private fun GitHubPanel() { Text("GitHub 工作台\n\n仓库、分支、Tag、Commit、文件、Issue、PR、Actions、Workflow 和 Artifact。\n远程修改必须明确确认。", Modifier.padding(top = 24.dp)) }
@Composable private fun SettingsPanel() { Text("设置\n\n语言：简体中文 / 繁體中文\n离线缓存 · 同步 · 诊断 · 桌宠 · GitHub 授权", Modifier.padding(top = 24.dp)) }
