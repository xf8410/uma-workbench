package com.uma.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.WorkspaceContextPromptComposer
import com.uma.workbench.agent.WorkspaceContextAttachment
import com.uma.workbench.agent.ActiveWorkspaceDocument
import com.uma.workbench.agent.AiGenerationPhase
import com.uma.workbench.data.MessageEntity

@Composable
fun AiChatScreen(vm: AiChatViewModel, openConfiguration: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val activeDocument by vm.activeDocument.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val attachmentMessage by vm.attachmentMessage.collectAsStateWithLifecycle()
    val loadingAttachment by vm.loadingAttachment.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showRangeDialog by remember { mutableStateOf(false) }
    var showModelContext by remember { mutableStateOf(false) }
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
            Row {
                TextButton(onClick = { showModelContext = true }) { Text("模型上下文") }
                TextButton(onClick = { vm.newConversation() }) { Icon(Icons.Default.AddComment, null); Text("新对话") }
                TextButton(onClick = openConfiguration) { Text("AI 配置") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(if (message.role.equals("user", true)) "你" else "AI", style = MaterialTheme.typography.labelMedium); Text(message.content); if (!message.role.equals("user", true)) Text(listOfNotNull(message.status, message.modelUsed, message.tokenCount?.let { "Token $it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall) } }
            }
            if (liveVisible) item("live-${generation.requestId}") {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("AI", style = MaterialTheme.typography.labelMedium); Text(generation.completeText); Text("${generation.statusLabel} · ${generation.usageLabel}", style = MaterialTheme.typography.labelSmall) } }
            }
        }
        ContextAttachmentPanel(
            activeDocument = activeDocument,
            attachments = attachments,
            loading = loadingAttachment,
            message = attachmentMessage,
            onAddRange = { showRangeDialog = true },
            onRemove = vm::removeAttachment
        )
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
    if (showRangeDialog) CurrentFileRangeDialog(
        document = activeDocument,
        onDismiss = { showRangeDialog = false },
        onConfirm = { start, end -> vm.attachCurrentFileRange(start, end); showRangeDialog = false }
    )
    if (showModelContext) ModelContextDialog(
        model = selection?.modelId,
        providerName = provider?.name,
        history = messages,
        draft = input,
        attachments = attachments,
        onDismiss = { showModelContext = false }
    )
}

@Composable
private fun ModelContextDialog(
    model: String?,
    providerName: String?,
    history: List<MessageEntity>,
    draft: String,
    attachments: List<WorkspaceContextAttachment>,
    onDismiss: () -> Unit
) {
    val exactDraft = WorkspaceContextPromptComposer.compose(draft, attachments)
    val completeContext = buildString {
        history.forEachIndexed { index, message ->
            append("[$index] role=${message.role.lowercase()} characters=${message.content.length}\n")
            append(message.content)
            append("\n\n")
        }
        append("[${history.size}] role=user characters=${exactDraft.length} draft=true\n")
        append(exactDraft)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("客户端可见的模型上下文") },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("提供商：${providerName ?: "未选择"} · 模型：${model ?: "未选择"}", style = MaterialTheme.typography.labelMedium)
                Text("消息 ${history.size + 1} 条 · 完整字符数 ${completeContext.length} · 待发送附件 ${attachments.size} 个", style = MaterialTheme.typography.labelSmall)
                Text("以下内容是客户端在点击发送时构造的完整消息上下文。服务商未通过 API 返回的隐藏指令和模型内部状态不在客户端可读取范围内。", style = MaterialTheme.typography.labelSmall)
                Surface(Modifier.weight(1f).fillMaxWidth()) {
                    Text(
                        completeContext,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        softWrap = false
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ContextAttachmentPanel(
    activeDocument: ActiveWorkspaceDocument?,
    attachments: List<WorkspaceContextAttachment>,
    loading: Boolean,
    message: String,
    onAddRange: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("上下文附件（本轮实际发送）", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onAddRange, enabled = activeDocument != null && !loading) {
                Icon(Icons.Default.AttachFile, null)
                Text(if (loading) "读取中" else "当前文件范围")
            }
        }
        if (activeDocument == null) Text("当前没有打开文件", style = MaterialTheme.typography.labelSmall)
        attachments.forEach { attachment ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("${attachment.title} L${attachment.startLine}-L${attachment.endLine}", style = MaterialTheme.typography.labelMedium)
                        Text(attachment.uri, style = MaterialTheme.typography.labelSmall)
                        Text("实际发送 ${attachment.sentCharacterCount} 字符 · 完整文件 ${attachment.completeCharacterCount} 字符 / ${attachment.totalLines} 行", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { onRemove(attachment.id) }) { Icon(Icons.Default.Close, "取消选择此附件") }
                }
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.labelSmall, maxLines = 4)
    }
}

@Composable
private fun CurrentFileRangeDialog(
    document: ActiveWorkspaceDocument?,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var startText by remember { mutableStateOf("1") }
    var endText by remember { mutableStateOf("200") }
    val start = startText.toIntOrNull()
    val end = endText.toIntOrNull()
    val valid = document != null && start != null && end != null && start >= 1 && end >= start
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("附加当前文件范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(document?.let { "${it.title}\n${it.uri}" } ?: "当前没有打开文件", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { startText = it }, label = { Text("起始行") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(endText, { endText = it }, label = { Text("结束行") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("只有确认后的实际行范围会进入本轮附件；卡片会显示完整文件长度和实际发送长度。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(start!!, end!!) }, enabled = valid) { Text("附加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
