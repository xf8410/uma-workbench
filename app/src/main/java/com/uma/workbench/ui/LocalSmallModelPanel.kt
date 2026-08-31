package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compact runtime-switch row for the AI chat screen: toggles the on-device small model runtime.
 */
@Composable
fun LocalRuntimeSwitchRow(vm: AiChatViewModel) {
    val localActive by vm.localRuntime.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (localActive) "运行时：本机小模型" else "本机", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        Switch(checked = localActive, onCheckedChange = { vm.setLocalRuntime(it) })
    }
}

/**
 * 本机小模型配置区（AI 配置页可折叠 section）。
 */
@Composable
fun LocalSmallModelSettingsSection(vm: LocalSmallModelViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("本机小模型（llama.cpp server / Ollama on Termux 等）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起 ▾" else "展开 ▸", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) LocalSmallModelSection(vm)
    }
}

/**
 * 本机小模型运行时选择 + 端点编辑 + 连接测试 + 启用开关。
 */
@Composable
fun LocalSmallModelSection(vm: LocalSmallModelViewModel) {
    val ep by vm.endpoint.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val runtimeId by vm.runtimeId.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    var baseUrl by remember(ep.baseUrl) { mutableStateOf(ep.baseUrl) }
    var model by remember(ep.model) { mutableStateOf(ep.model) }
    var token by remember(ep.authToken) { mutableStateOf(ep.authToken) }
    var label by remember(ep.label) { mutableStateOf(ep.label) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本机小模型", style = MaterialTheme.typography.titleMedium)
        Text("推理服务跑在本机（如 Termux 里的 llama.cpp server / Ollama），通过 127.0.0.1 回环接入，权重不进 APK。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.runtimes.forEach { runtime ->
                FilterChip(selected = runtime.id == runtimeId, onClick = { vm.selectRuntime(runtime.id) }, label = { Text(runtime.label, style = MaterialTheme.typography.labelSmall) })
            }
        }
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("地址（http://127.0.0.1:8080）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("可选 Token（留空表示无鉴权）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(label, { label = it }, label = { Text("显示名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.update(baseUrl, model, token, label); vm.save() }, enabled = !testing) { Text("保存") }
            OutlinedButton(onClick = { vm.update(baseUrl, model, token, label); vm.testConnection() }, enabled = !testing && ep.configured) { Text(if (testing) "测试中…" else "测试连接") }
            TextButton(onClick = vm::clear, enabled = !testing) { Text("清除") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.labelSmall)
        testResult?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = enabled, onCheckedChange = { vm.setEnabled(it) }, enabled = ep.configured)
            Text(if (enabled) "聊天运行时：本机小模型" else "聊天运行时：云端/局域网", style = MaterialTheme.typography.bodySmall)
        }
    }
}
