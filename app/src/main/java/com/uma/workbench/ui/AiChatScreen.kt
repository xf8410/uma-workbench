package com.uma.workbench.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.agent.*
import com.uma.workbench.data.ConversationEntity

@Composable fun AiChatScreen(vm:AiChatViewModel,openConfiguration:()->Unit){
 val messages by vm.messages.collectAsStateWithLifecycle();val generation by vm.generation.collectAsStateWithLifecycle();val catalog by vm.catalog.collectAsStateWithLifecycle();val document by vm.activeDocument.collectAsStateWithLifecycle();val attachments by vm.attachments.collectAsStateWithLifecycle();val rounds by vm.agentRounds.collectAsStateWithLifecycle();val persisted by vm.persistedSubAgentRuns.collectAsStateWithLifecycle();val conversations by vm.conversations.collectAsStateWithLifecycle();val currentId by vm.conversationId.collectAsStateWithLifecycle();val sel by vm.selection.collectAsStateWithLifecycle();val lanActive by vm.lanRuntime.collectAsStateWithLifecycle();var input by remember{mutableStateOf("")};var showHistory by remember{mutableStateOf(false)};var showSearch by remember{mutableStateOf(false)};val listState=rememberLazyListState();val selection=catalog.defaultModel;val live=generation.phase==AiGenerationPhase.GENERATING||generation.phase==AiGenerationPhase.WAITING_FOR_NETWORK||generation.phase==AiGenerationPhase.RESUMING;val canSubmit=input.isNotBlank()&&(selection!=null||lanActive)
 LaunchedEffect(messages.size,rounds.size,persisted.size,generation.completeText){val count=messages.size+rounds.size+persisted.size+if(live)1 else 0;if(count>0)listState.scrollToItem(count-1)}
 Column(Modifier.fillMaxSize().padding(10.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("AI 只读 Agent",style=MaterialTheme.typography.titleLarge);Text(document?.title?:"未打开工作区文件",style=MaterialTheme.typography.labelSmall)};Row{TextButton({showHistory=true},enabled=document!=null&&!live){Icon(Icons.Default.History,null);Text("历史")};TextButton(vm::newConversation,enabled=!live){Icon(Icons.Default.AddComment,null);Text("新对话")};SingleChatModeControls();SingleChatAuthorizationControls(vm);LanRuntimeSwitchRow(vm);TextButton(openConfiguration){Text("配置")}}};if(generation.phase==AiGenerationPhase.WAITING_FOR_NETWORK){Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("网络连接中断",style=MaterialTheme.typography.titleSmall);Text("已保存 ${"%,d".format(generation.partialCharacterCount)} 个字符",style=MaterialTheme.typography.labelSmall)};Row{TextButton(vm::retryAfterNetwork){Text("尝试继续")};TextButton(vm::stopAndKeep){Text("停止并保留")}}}}};LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)){items(messages,key={it.id}){m->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(if(m.role.equals("user",true))"你" else "AI",style=MaterialTheme.typography.labelMedium);Text(m.content);if(!m.role.equals("user",true))Text(listOfNotNull(m.status,m.modelUsed,m.tokenCount?.let{"Token $it"}).joinToString(" · "),style=MaterialTheme.typography.labelSmall);persisted.forEach{run->if(run.messageId==m.id)PersistedSubAgentReportPanel(run.report.resultId,run.report.totalCharacterCount,vm::readPersistedToolResult,Modifier.padding(top=8.dp))}}}};items(rounds,key={"round-${it.index}"}){AgentRoundCard(it,vm)};if(live)item("live"){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text("AI · 第 ${rounds.size+1} 轮");Text(generation.completeText.ifEmpty{"等待模型或执行只读工具…"})}}}};Row(Modifier.fillMaxWidth()){TextButton({showSearch=true},enabled=document!=null&&!live){Icon(Icons.Default.Search,null);Text("搜索")};TextButton({vm.attachCurrentFileRange(1,Int.MAX_VALUE)},enabled=document!=null&&!live){Icon(Icons.Default.AttachFile,null);Text("完整文件")};Text("附件 ${attachments.size}",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(12.dp))};attachments.forEach{a->AssistChip(onClick={vm.removeAttachment(a.id)},label={Text("${a.title} · 完整 ${a.completeCharacterCount} 字符")},trailingIcon={Icon(Icons.Default.Close,null)})};Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(input,{input=it},label={Text(if(lanActive)"发送消息（局域网模型）" else "发送消息")},enabled=generation.canSend,modifier=Modifier.weight(1f));IconButton(onClick={if(generation.canInterrupt)vm.interrupt()else if(canSubmit){vm.send(input);input=""}},enabled=generation.canInterrupt||canSubmit){Icon(if(generation.canInterrupt)Icons.Default.Stop else Icons.Default.Send,null)}}}
 if(showHistory)ConversationHistoryDialog(conversations,currentId,sel,vm::enterSelection,vm::toggleSelection,vm::selectAllVisible,vm::clearSelection,vm::exitSelection,vm::deleteSelectedConversations,{showHistory=false}){vm.openConversation(it);showHistory=false};if(showSearch)WorkspaceSearchDialog(vm){showSearch=false}
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class) @Composable private fun ConversationHistoryDialog(cs:List<ConversationEntity>,current:String?,sel:ConversationSelectionState,enter:(String?)->Unit,toggle:(String)->Unit,selectAll:(List<String>)->Unit,clear:()->Unit,exit:()->Unit,delete:()->Unit,dismiss:()->Unit,open:(String)->Unit){
 val showDeleteConfirm=remember{mutableStateOf(false)}
 AlertDialog(onDismissRequest={if(sel.selecting)exit()else dismiss()},confirmButton={if(sel.selecting){Row{TextButton(clear,enabled=sel.selectedIds.isNotEmpty()){Text("清空选择")};TextButton(exit){Text("取消")};Button(delete,enabled=sel.selectedIds.isNotEmpty()&&!sel.deleting){if(sel.deleting)Text("删除中…")else Text("删除 ${sel.selectedCount}")}}}else TextButton(dismiss){Text("关闭")}},title={if(sel.selecting)Text("已选 ${sel.selectedCount} 个对话")else Text("当前工作区对话")},text={Column(Modifier.fillMaxWidth()){if(!sel.selecting){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton({enter(null)},enabled=cs.isNotEmpty()){Text("管理")}}}else{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton({selectAll(cs.map{it.id})}){Text("全选")}}};if(sel.error!=null)Text(sel.error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)};LazyColumn(Modifier.fillMaxWidth()){if(cs.isEmpty())item{Text("暂无历史对话")};items(cs,key={it.id}){c->val isSelected=sel.selecting&&c.id in sel.selectedIds;ListItem(headlineContent={Text(c.title)},supportingContent={Text("${c.agentMode} · ${c.updatedAt}")},leadingContent={if(sel.selecting)Checkbox(isSelected,{toggle(c.id)})else if(c.id==current)Icon(Icons.Default.Chat,null)},trailingContent={if(sel.selecting&&isSelected)Icon(Icons.Default.Check,null)},modifier=Modifier.fillMaxWidth().combinedClickable(onClick={if(sel.selecting)toggle(c.id)else open(c.id)},onLongClick={if(!sel.selecting)enter(c.id)}));HorizontalDivider()}}})
 if(showDeleteConfirm.value){AlertDialog(onDismissRequest={showDeleteConfirm.value=false},confirmButton={Button({showDeleteConfirm.value=false;delete()}){Text("确认删除")}},dismissButton={TextButton({showDeleteConfirm.value=false}){Text("取消")}},title={Text("删除 ${sel.selectedCount} 个对话")},text={Text("将永久删除选中的对话及其消息，不可恢复。")})}
}@Composable private fun AgentRoundCard(round:ReadonlyAgentRound,vm:AiChatViewModel){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text("Agent 第 ${round.index} 轮 · ${round.toolCalls.size} 个工具",style=MaterialTheme.typography.labelMedium);round.toolCalls.zip(round.toolOutcomes).forEach{(call,outcome)->when(outcome){is AgentToolOutcome.Success->{val r=outcome.result;if(call.name=="delegate_subagents")PersistedSubAgentReportPanel(r.resultId,r.totalCharacterCount,vm::readPersistedToolResult)else Text("${call.name} · ${r.startOffset}-${r.endOffsetExclusive}/${r.totalCharacterCount}${if(r.complete)" 完整" else " 未完整"}",style=MaterialTheme.typography.labelSmall)};is AgentToolOutcome.Failure->Text("${call.name} · 失败",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)}}}}}
/**
 * Single-chat mode switching control: mode dropdown + transition confirmation + approval dialog.
 * Mirrors the group chat mode controls in [AgentPartnerPanel] but for the single-chat [AiChatScreen].
 */
@Composable
internal fun SingleChatModeControls() {
    val context = LocalContext.current
    val app = context.applicationContext as WorkbenchApplication
    val currentMode by ActiveModeBridge.mode.collectAsStateWithLifecycle()
    val pendingApprovals by app.toolApprovalGate.pending.collectAsStateWithLifecycle()
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var pendingModeTransition by remember { mutableStateOf<ModeTransition?>(null) }

    LaunchedEffect(Unit) {
        val stored = app.modePreferences.getString("agent_mode", null)
        ActiveModeBridge.publish(AgentMode.fromStorageKey(stored))
    }

    // Mode transition confirmation dialog
    pendingModeTransition?.let { transition ->
        val warning = transition.warningMessage()
        if (warning != null) {
            AlertDialog(
                onDismissRequest = { pendingModeTransition = null },
                confirmButton = {
                    Button(onClick = {
                        app.modePreferences.edit { putString("agent_mode", transition.to.storageKey) }
                        ActiveModeBridge.publish(transition.to)
                        pendingModeTransition = null
                    }) { Text("确认切换") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingModeTransition = null }) { Text("取消") }
                },
                title = { Text("切换 Agent 模式") },
                text = { Text(warning) }
            )
        } else {
            app.modePreferences.edit { putString("agent_mode", transition.to.storageKey) }
            ActiveModeBridge.publish(transition.to)
            pendingModeTransition = null
        }
    }

    // Tool approval dialog
    if (pendingApprovals.isNotEmpty()) {
        ToolApprovalDialog(app.toolApprovalGate)
    }

    // Mode dropdown
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                currentMode.label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            IconButton(onClick = { modeMenuExpanded = true }, modifier = Modifier.size(28.dp)) {
                Text("🎛", fontSize = 16.sp)
            }
        }
        DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
            AgentMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text("${mode.label}（${mode.description.take(20)}）") },
                    onClick = {
                        modeMenuExpanded = false
                        val transition = ModeTransition(currentMode, mode)
                        if (transition.requiresConfirmation) {
                            pendingModeTransition = transition
                        } else {
                            app.modePreferences.edit { putString("agent_mode", mode.storageKey) }
                            ActiveModeBridge.publish(mode)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Single-chat GitHub authorization control: 🔑 button + authorization dialog.
 * Issuing a token sends it as a user message so the Agent can consume it from context.
 */
@Composable
internal fun SingleChatAuthorizationControls(vm: AiChatViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as WorkbenchApplication
    val authorization = remember { GitHubAuthorizationController(app.githubConfirmationStore) }
    var showingAuthorization by remember { mutableStateOf(false) }
    if (showingAuthorization) {
        GitHubAuthorizationDialog(
            controller = authorization,
            onIssued = { token ->
                showingAuthorization = false
                vm.send(
                    "[GitHub授权] 已发放贡献流一次性令牌：$token " +
                        "（10分钟内有效；fork/分支/PR 各一次，文件提交最多8次，PR 创建后整张令牌自动作废）。" +
                        "Agent：将此令牌作为 github_contribute_fork / github_contribute_branch / github_contribute_write / github_contribute_pr 的 confirmationId 参数。"
                )
            },
            onDismiss = { showingAuthorization = false }
        )
    }
    IconButton(onClick = { showingAuthorization = true }, modifier = Modifier.size(28.dp)) {
        Text("🔑", fontSize = 16.sp)
    }
}
