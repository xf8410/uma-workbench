package com.uma.workbench.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.AiGenerationPhase

@Composable
fun AiChatScreen(vm: AiChatViewModel, openConfiguration: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val selection = catalog.defaultModel
    val provider = catalog.providers.firstOrNull { it.id == selection?.providerId }
    val liveVisible = generation.phase != AiGenerationPhase.IDLE && generation.completeText.isNotEmpty()
    LaunchedEffect(messages.size, generation.completeText) {
        val count = messages.size + if (liveVisible) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("AI 聊天", style = MaterialTheme.typography.titleLarge); Text(if (selection == null) "尚未选择模型" else "${provider?.name ?: "未知提供商"} / ${selection.modelId}", style = MaterialTheme.typography.labelMedium) }
            Row { TextButton(onClick = { vm.newConversation() }) { Icon(Icons.Default.AddComment, null); Text("新对话") }; TextButton(onClick = openConfiguration) { Text("AI 配置") } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(if (message.role.equals("user", true)) "你" else "AI", style = MaterialTheme.typography.labelMedium); Text(message.content); if (!message.role.equals("user", true)) Text(listOfNotNull(message.status, message.modelUsed, message.tokenCount?.let { "Token $it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall) } }
            }
            if (liveVisible) item("live-${generation.requestId}") {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("AI", style = MaterialTheme.typography.labelMedium); Text(generation.completeText); Text("${generation.statusLabel} · ${generation.usageLabel}", style = MaterialTheme.typography.labelSmall) } }
            }
        }
        if (generation.phase != AiGenerationPhase.IDLE) Text("${generation.statusLabel} · ${generation.usageLabel}", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, label = { Text("发送消息") }, enabled = generation.canSend, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5)
            IconButton(onClick = {
                if (generation.canInterrupt) vm.interrupt()
                else if (input.isNotBlank() && selection != null) { vm.send(input); input = "" }
            }, enabled = generation.canInterrupt || (input.isNotBlank() && selection != null)) {
                Icon(if (generation.canInterrupt) Icons.Default.Stop else Icons.Default.Send, if (generation.canInterrupt) "停止生成" else "发送")
            }
        }
    }
}
