package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class Tab(val title: String) { Chat("对话"), Audit("审计"), History("历史"), Settings("设置") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WorkbenchApp() }
    }
}

@Composable
private fun WorkbenchApp() {
    var tab by remember { mutableStateOf(Tab.Chat) }
    val tabs = Tab.entries
    Scaffold(bottomBar = {
        NavigationBar { tabs.forEach { item ->
            NavigationBarItem(selected = tab == item, onClick = { tab = item },
                icon = { Icon(if (item == Tab.Chat) Icons.Default.Chat else if (item == Tab.Audit) Icons.Default.Folder else if (item == Tab.History) Icons.Default.History else Icons.Default.Settings, item.title) },
                label = { Text(item.title) })
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("UMA Workbench", style = MaterialTheme.typography.headlineSmall)
            Text("Android 研究与开发工作台", style = MaterialTheme.typography.bodyMedium)
            when (tab) {
                Tab.Chat -> ChatPanel()
                Tab.Audit -> AuditPanel()
                Tab.History -> HistoryPanel()
                Tab.Settings -> SettingsPanel()
            }
        }
    }
}

@Composable private fun ChatPanel() {
    var input by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Card(Modifier.fillMaxWidth()) { Text("新对话将自动加载项目规则、相关记忆和当前项目状态。", Modifier.padding(16.dp)) }
        Text("暂无消息。你可以导入资料、创建审计任务，或开始中文对话。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("输入消息") })
            Button(onClick = { input = "" }, enabled = input.isNotBlank()) { Text("发送") }
        }
    }
}

@Composable private fun AuditPanel() {
    val features = listOf("多仓库子 Agent 审计", "SO / IL2CPP / metadata 索引", "DB / Master / MetaMD5 分析", "端点与证据链关联", "离线缓存与断点任务")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
        item { Text("项目审计", style = MaterialTheme.typography.titleLarge) }
        items(features) { feature -> Card(Modifier.fillMaxWidth()) { Text(feature + "\n待实现模块已纳入架构。", Modifier.padding(16.dp)) } }
    }
}

@Composable private fun HistoryPanel() { Text("历史任务和工具日志\n\n暂无已完成任务。", Modifier.padding(top = 24.dp)) }
@Composable private fun SettingsPanel() { Text("设置\n\n语言：简体中文 / 繁體中文\n离线缓存、同步、诊断和桌宠设置将在后续页面提供。", Modifier.padding(top = 24.dp)) }
