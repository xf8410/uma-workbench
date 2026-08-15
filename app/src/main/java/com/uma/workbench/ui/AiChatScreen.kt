package com.uma.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.*
import com.uma.workbench.data.MessageEntity

@Composable fun AiChatScreen(vm: AiChatViewModel, openConfiguration: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle(); val generation by vm.generation.collectAsStateWithLifecycle(); val catalog by vm.catalog.collectAsStateWithLifecycle(); val activeDocument by vm.activeDocument.collectAsStateWithLifecycle(); val attachments by vm.attachments.collectAsStateWithLifecycle(); val attachmentMessage by vm.attachmentMessage.collectAsStateWithLifecycle(); val loadingAttachment by vm.loadingAttachment.collectAsStateWithLifecycle(); val rounds by vm.agentRounds.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }; var showRange by remember { mutableStateOf(false) }; var showSearch by remember { mutableStateOf(false) }; var showContext by remember { mutableStateOf(false) }; val listState = rememberLazyListState(); val selection = catalog.defaultModel; val provider = catalog.providers.firstOrNull { it.id == selection?.providerId }; val live = generation.phase == AiGenerationPhase.GENERATING
    LaunchedEffect(messages.size, generation.completeText, rounds.size) { val count = messages.size + rounds.size + if (live) 1 else 0; if (count > 0) listState.scrollToItem(count - 1) }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text("AI 只读 Agent", style = MaterialTheme.typography.titleLarge); Text(if (selection == null) "尚未选择模型" else "${provider?.name ?: "未知提供商"} / ${selection.modelId}", style = MaterialTheme.typography.labelMedium) }; Row { TextButton({ showContext = true }) { Text("模型上下文") }; TextButton(vm::newConversation) { Icon(Icons.Default.AddComment, null); Text("新对话") }; TextButton(openConfiguration) { Text("AI 配置") } } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { m -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(if (m.role.equals("user", true)) "你" else "AI", style = MaterialTheme.typography.labelMedium); Text(m.content); if (!m.role.equals("user", true)) Text(listOfNotNull(m.status, m.modelUsed, m.tokenCount?.let { "Token $it" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall) } } }
            items(rounds, key = { "round-${it.index}" }) { AgentRoundCard(it) }
            if (live) item("live") { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text("AI · 第 ${rounds.size + 1} 轮", style = MaterialTheme.typography.labelMedium); Text(generation.completeText.ifEmpty { "正在等待模型或执行只读工具…" }); Text(generation.statusLabel, style = MaterialTheme.typography.labelSmall) } } }
        }
        ContextPanel(activeDocument, attachments, loadingAttachment, attachmentMessage, { showRange = true }, { showSearch = true }, vm::removeAttachment)
        if (generation.phase != AiGenerationPhase.IDLE) Text("${generation.statusLabel} · ${generation.usageLabel}", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { OutlinedTextField(input, { input = it }, label = { Text("发送消息") }, enabled = generation.canSend, modifier = Modifier.weight(1f), maxLines = 5); IconButton({ if (generation.canInterrupt) vm.interrupt() else if (input.isNotBlank() && selection != null) { vm.send(input); input = "" } }, enabled = generation.canInterrupt || (input.isNotBlank() && selection != null)) { Icon(if (generation.canInterrupt) Icons.Default.Stop else Icons.Default.Send, null) } }
    }
    if (showRange) RangeDialog(activeDocument, { showRange = false }) { a, b -> vm.attachCurrentFileRange(a, b); showRange = false }
    if (showSearch) WorkspaceSearchDialog(vm) { showSearch = false }
    if (showContext) ContextDialog(selection?.modelId, provider?.name, messages, input, attachments) { showContext = false }
}

@Composable private fun AgentRoundCard(round: ReadonlyAgentRound) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Agent 第 ${round.index} 轮 · ${round.toolCalls.size} 个工具", style = MaterialTheme.typography.labelMedium); if (round.assistantText.isNotBlank()) Text(round.assistantText); round.toolCalls.zip(round.toolOutcomes).forEach { (call, outcome) -> val status = when (outcome) { is AgentToolOutcome.Success -> { val r = outcome.result; "成功 · ${r.startOffset}-${r.endOffsetExclusive}/${r.totalCharacterCount}${if (r.complete) " · 完整" else " · 可续读 ${r.nextOffset}"}" }; is AgentToolOutcome.Failure -> "失败" }; Text("${call.name} · $status", style = MaterialTheme.typography.labelSmall) } } } }
@Composable private fun ContextPanel(d: ActiveWorkspaceDocument?, a: List<WorkspaceContextAttachment>, loading: Boolean, message: String, range: () -> Unit, search: () -> Unit, remove: (String) -> Unit) { Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("本轮上下文", style = MaterialTheme.typography.labelMedium); Row { TextButton(search, enabled = d != null && !loading) { Icon(Icons.Default.Search, null); Text("搜索") }; TextButton(range, enabled = d != null && !loading) { Icon(Icons.Default.AttachFile, null); Text("文件范围") } } }; a.forEach { x -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(8.dp)) { Column(Modifier.weight(1f)) { Text("${x.title} L${x.startLine}-${x.endLine}"); Text("发送 ${x.sentCharacterCount} / 完整 ${x.completeCharacterCount}", style = MaterialTheme.typography.labelSmall) }; IconButton({ remove(x.id) }) { Icon(Icons.Default.Close, null) } } } }; if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.labelSmall) } }
@Composable private fun RangeDialog(d: ActiveWorkspaceDocument?, dismiss: () -> Unit, confirm: (Int, Int) -> Unit) { var s by remember { mutableStateOf("1") }; var e by remember { mutableStateOf("200") }; val a=s.toIntOrNull(); val b=e.toIntOrNull(); AlertDialog(dismiss, { TextButton({ confirm(a!!,b!!) }, enabled=d!=null&&a!=null&&b!=null&&a>=1&&b>=a){Text("附加")} }, title={Text("附加当前文件范围")}, text={Row { OutlinedTextField(s,{s=it},label={Text("起始")},modifier=Modifier.weight(1f)); OutlinedTextField(e,{e=it},label={Text("结束")},modifier=Modifier.weight(1f)) }}, dismissButton={TextButton(dismiss){Text("取消")}}) }
@Composable private fun ContextDialog(model:String?, provider:String?, history:List<MessageEntity>, draft:String, attachments:List<WorkspaceContextAttachment>, dismiss:()->Unit) { val exact=WorkspaceContextPromptComposer.compose(draft,attachments); val all=buildString { history.forEach { append("role=${it.role}\n${it.content}\n\n") }; append("role=user\n$exact") }; AlertDialog(dismiss,{TextButton(dismiss){Text("关闭")}},title={Text("客户端可见上下文")},text={Column(Modifier.fillMaxHeight(.8f)){Text("${provider?:"未选择"} / ${model?:"未选择"}"); Text(all,Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,softWrap=false)}}) }
