package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.*

@Composable
fun AiConfigurationScreen(vm: AiConfigurationViewModel) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val syncing by vm.syncingProviderIds.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AiProviderProfile?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("AI 配置", style = MaterialTheme.typography.headlineSmall)
        TabRow(tab) { Tab(tab == 0, { tab = 0 }, text = { Text("提供商") }); Tab(tab == 1, { tab = 1 }, text = { Text("模型") }) }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
        if (tab == 0) ProviderList(catalog, syncing, { editing = it }, vm::synchronize) { editing = AiProviderProfile(name = "", baseUrl = "") }
        else ModelList(catalog, syncing, vm::synchronizeAll, vm::selectModel)
    }
    editing?.let { profile -> ProviderEditor(profile, onDismiss = { editing = null }, onSave = { vm.saveProvider(it); editing = null }) }
}

@Composable private fun ProviderList(catalog: AiProviderCatalog, syncing: Set<String>, edit: (AiProviderProfile) -> Unit, sync: (String) -> Unit, add: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(catalog.providers, key = { it.id }) { provider ->
            ListItem(
                headlineContent = { Text(provider.name) },
                supportingContent = { Text("${provider.baseUrl}\n${provider.credentials.size} 个密钥 · ${provider.models.size} 个模型") },
                trailingContent = { TextButton(enabled = provider.configured && provider.id !in syncing, onClick = { sync(provider.id) }) { Text(if (provider.id in syncing) "同步中" else "同步") } },
                modifier = Modifier.clickable { edit(provider) }
            ); HorizontalDivider()
        }
        item { ListItem(headlineContent = { Text("添加提供商") }, leadingContent = { Icon(Icons.Default.Add, null) }, modifier = Modifier.clickable(onClick = add)) }
    }
}

@Composable private fun ModelList(catalog: AiProviderCatalog, syncing: Set<String>, syncAll: () -> Unit, select: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ListItem(headlineContent = { Text("从所有提供商同步") }, supportingContent = { Text("获取所有已配置 API 的最新模型列表") }, leadingContent = { Icon(Icons.Default.Refresh, null) }, modifier = Modifier.clickable(enabled = syncing.isEmpty(), onClick = syncAll)) }
        catalog.providers.forEach { provider ->
            item { Text(provider.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
            items(provider.models, key = { "${provider.id}:$it" }) { model ->
                val selected = catalog.defaultModel == AiModelSelection(provider.id, model)
                ListItem(headlineContent = { Text(model) }, supportingContent = { Text(if (selected) "默认模型" else provider.name) }, leadingContent = { RadioButton(selected, { select(provider.id, model) }) }, modifier = Modifier.clickable { select(provider.id, model) })
            }
        }
    }
}

@Composable private fun ProviderEditor(initial: AiProviderProfile, onDismiss: () -> Unit, onSave: (AiProviderProfile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }; var url by remember { mutableStateOf(initial.baseUrl) }
    var chatPath by remember { mutableStateOf(initial.chatPath) }; var modelsPath by remember { mutableStateOf(initial.modelsPath) }
    var credentials by remember { mutableStateOf(initial.credentials) }; var label by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial.selectedCredentialId) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial.name.isBlank()) "添加提供商" else initial.name) }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("名称") }) }
            item { OutlinedTextField(url, { url = it }, label = { Text("基础 URL") }) }
            item { OutlinedTextField(chatPath, { chatPath = it }, label = { Text("聊天路径") }) }
            item { OutlinedTextField(modelsPath, { modelsPath = it }, label = { Text("模型列表路径") }) }
            items(credentials, key = { it.id }) { key -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Row { RadioButton(selected == key.id, { selected = key.id }); Column { Text(key.label); Text(key.masked, style = MaterialTheme.typography.labelSmall) } }; TextButton({ credentials = credentials.filterNot { it.id == key.id }; if (selected == key.id) selected = null }) { Text("删除") } } }
            item { OutlinedTextField(label, { label = it }, label = { Text("密钥名称") }); OutlinedTextField(secret, { secret = it }, label = { Text("API 密钥") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)); TextButton(enabled = label.isNotBlank() && secret.isNotBlank(), onClick = { val key = AiApiCredential(label = label, secret = secret); credentials = credentials + key; if (selected == null) selected = key.id; label = ""; secret = "" }) { Text("添加密钥") } }
        }
    }, confirmButton = { TextButton(onClick = { onSave(initial.copy(name = name, baseUrl = url, chatPath = chatPath, modelsPath = modelsPath, credentials = credentials, selectedCredentialId = selected)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
