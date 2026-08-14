package com.uma.workbench.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uma.workbench.data.ConversationEntity
import com.uma.workbench.data.MessageEntity
import com.uma.workbench.hlpatch.HlpatchClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AgentPanel(
    conversations: List<ConversationEntity>,
    messages: List<MessageEntity>,
    onSendMessage: (String) -> Unit,
    onNewConversation: () -> Unit,
    hlpatchState: HlpatchClient.ConnectionState,
    onHlpatchConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Surface(modifier = modifier, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Agent", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (hlpatchState == HlpatchClient.ConnectionState.DISCONNECTED) {
                    TextButton(onClick = onHlpatchConnect) { Text("连接 hlpatch", style = MaterialTheme.typography.labelSmall) }
                }
                IconButton(onClick = onNewConversation) { Icon(Icons.Default.Add, contentDescription = "新对话") }
            }
            Divider()

            // 消息列表
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("工作区已就绪", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Agent 可以读取工作区中的文件、索引和 hlpatch 状态。\n输入问题或选择工具开始。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            // 输入栏
            Divider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…", style = MaterialTheme.typography.bodySmall) },
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(onClick = { if (input.isNotBlank()) { onSendMessage(input.trim()); input = "" } }) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(msg.content, style = MaterialTheme.typography.bodySmall, maxLines = 20, overflow = TextOverflow.Ellipsis)
                if (msg.status != "COMPLETE") {
                    Spacer(Modifier.height(4.dp))
                    Text(msg.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
