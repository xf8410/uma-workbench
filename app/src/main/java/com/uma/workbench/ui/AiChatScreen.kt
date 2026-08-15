package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.ActiveWorkspaceDocument
import com.uma.workbench.agent.AgentToolOutcome
import com.uma.workbench.agent.AiGenerationPhase
import com.uma.workbench.agent.ReadonlyAgentRound
import com.uma.workbench.data.ConversationEntity

@Composable
fun AiChatScreen(vm: AiChatViewModel, openConfiguration: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val document by vm.activeDocument.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val rounds by vm.agentRounds.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val currentId by vm.conversationId.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selection = catalog.defaultModel
    val live = generation.phase == AiGenerationPhase.GENERATING

    LaunchedEffect(messages.size, rounds.size, generation.completeText) {
        val count = messages.size + rounds.size + if (live) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("AI 只读 Agent", style = MaterialTheme.typography.titleLarge)
                Text(document?.title ?: "未打开工作区文件", style = MaterialTheme.typography.labelSmall)
            }
            Row {
                TextButton(onClick = { showHistory = true }, enabled = document != null && !live) { Icon(Icons.Default.History, null); Text("历史") }
                TextButton(onClick = vm::newConversation, enabled = !live) { Icon(Icons.Default.AddComment, null); Text("新对话") }
                TextButton(onClick = openConfiguration) { Text("配置") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                    Text(if (message.role.equals("user", true)) "你" else "AI", style = MaterialTheme.typography.labelMedium)
                    Text(message.content)
                    if (!message.role.equals("user", true)) Text(listOfNotNull(message.status, message.modelUsed, message.tokenCount?.let { "Token $it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                } }
            }
            items(rounds, key = { "round-${it.index}" }) { AgentRoundCard(it) }
            if (live) item("live") { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("AI · 第 ${rounds.size + 1} 轮"); Text(generation.completeText.ifEmpty { "等待模型或执行只读工具…" }) } } }
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { showSearch = true }, enabled = document != null && !live) { Icon(Icons.Default.Search, null); Text("搜索") }
            TextButton(onClick = { showRange = true }, enabled = document != null && !live) { Icon(Icons.Default.AttachFile, null); Text("文件范围") }
            Text("附件 ${attachments.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
        }
        attachments.forEach { attachment -> AssistChip(onClick = { vm.removeAttachment(attachment.id) }, label = { Text("${attachment.title} L${attachment.startLine}-${attachment.endLine} · ${attachment.sentCharacterCount}/${attachment.completeCharacterCount}") }, trailingIcon = { Icon(Icons.Default.Close, null) }) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, label = { Text("发送消息") }, enabled = generation.canSend, modifier = Modifier.weight(1f), maxLines = 5)
            IconButton(onClick = { if (generation.canInterrupt) vm.interrupt() else if (input.isNotBlank() && selection != null) { vm.send(input); input = "" } }, enabled = generation.canInterrupt || (input.isNotBlank() && selection != null)) { Icon(if (generation.canInterrupt) Icons.Default.Stop else Icons.Default.Send, null) }
        }
    }
    if (showHistory) ConversationHistoryDialog(conversations, currentId, { showHistory = false }) { vm.openConversation(it); showHistory = false }
    if (showRange) FileRangeDialog(document, { showRange = false }) { start, end -> vm.attachCurrentFileRange(start, end); showRange = false }
    if (showSearch) WorkspaceSearchDialog(vm) { showSearch = false }
}

@Composable
private fun ConversationHistoryDialog(conversations: List<ConversationEntity>, currentId: String?, dismiss: () -> Unit, open: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } }, title = { Text("当前工作区对话") }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            if (conversations.isEmpty()) item { Text("暂无历史对话") }
            items(conversations, key = { it.id }) { conversation ->
                ListItem(headlineContent = { Text(conversation.title) }, supportingContent = { Text("${conversation.agentMode} · ${conversation.updatedAt}") }, leadingContent = { if (conversation.id == currentId) Icon(Icons.Default.Chat, null) }, modifier = Modifier.fillMaxWidth().clickable { open(conversation.id) })
                HorizontalDivider()
            }
        }
    })
}

@Composable
private fun AgentRoundCard(round: ReadonlyAgentRound) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
        Text("Agent 第 ${round.index} 轮 · ${round.toolCalls.size} 个工具", style = MaterialTheme.typography.labelMedium)
        round.toolCalls.zip(round.toolOutcomes).forEach { (call, outcome) ->
            val status = when (outcome) { is AgentToolOutcome.Success -> with(outcome.result) { "$startOffset-$endOffsetExclusive/$totalCharacterCount${if (complete) " 完整" else " 下一页 $nextOffset"}" }; is AgentToolOutcome.Failure -> "失败" }
            Text("${call.name} · $status", style = MaterialTheme.typography.labelSmall)
        }
    } }
}

@Composable
private fun FileRangeDialog(document: ActiveWorkspaceDocument?, dismiss: () -> Unit, confirm: (Int, Int) -> Unit) {
    var startText by remember { mutableStateOf("1") }; var endText by remember { mutableStateOf("200") }
    val start = startText.toIntOrNull(); val end = endText.toIntOrNull()
    AlertDialog(onDismissRequest = dismiss, confirmButton = { TextButton(onClick = { confirm(start!!, end!!) }, enabled = document != null && start != null && end != null && start > 0 && end >= start) { Text("附加") } }, title = { Text("附加文件范围") }, text = { Row { OutlinedTextField(startText, { startText = it }, label = { Text("起始行") }, modifier = Modifier.weight(1f)); OutlinedTextField(endText, { endText = it }, label = { Text("结束行") }, modifier = Modifier.weight(1f)) } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
