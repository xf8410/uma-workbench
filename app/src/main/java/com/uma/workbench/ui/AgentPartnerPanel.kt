package com.uma.workbench.ui

import androidx.compose.animation.animateColorAsState
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import com.uma.workbench.WorkbenchApplication
import com.uma.workbench.github.GitHubConfirmationStore
import com.uma.workbench.github.GitHubRemoteOperation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.AgentGenerationState
import com.uma.workbench.agent.AgentGroupPolicy
import com.uma.workbench.agent.AgentGroupMessageEntity
import com.uma.workbench.agent.AgentPartnerViewModel
import com.uma.workbench.agent.AgentProfileEntity
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun AgentPartnerPanel(
    viewModel: AgentPartnerViewModel,
    workspaceId: String,
    onDismiss: () -> Unit
) {
    viewModel.setWorkspace(workspaceId)
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as WorkbenchApplication
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val messages by viewModel.groupMessages.collectAsStateWithLifecycle()
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    var creatingProfile by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }
    var editingGroupSettings by remember { mutableStateOf(false) }

    val authorization = remember {
        val app = context.applicationContext as WorkbenchApplication
        GitHubAuthorizationController(app.githubConfirmationStore)
    }
    val currentMode by com.uma.workbench.agent.ActiveModeBridge.mode.collectAsStateWithLifecycle()
    val pendingApprovals by app.toolApprovalGate.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val stored = app.modePreferences.getString("agent_mode", null)
        com.uma.workbench.agent.ActiveModeBridge.publish(
            com.uma.workbench.agent.AgentMode.fromStorageKey(stored)
        )
    }
    if (pendingApprovals.isNotEmpty()) {
        ToolApprovalDialog(approvalGate = app.toolApprovalGate)
    }
    if (selectedGroup != null) {
        AgentGroupChatDialog(
            groupName = selectedGroup!!.name,
            messages = messages,
            generationState = generationState,
            onSend = viewModel::sendGroupMessage,
            onCancel = viewModel::cancelGroupGeneration,
            onRetry = viewModel::retryFailedMessage,
            onSettings = { editingGroupSettings = true },
            authorization = authorization,
            approvalGate = app.toolApprovalGate,
            onModeChange = { mode ->
                app.modePreferences.edit { putString("agent_mode", mode.storageKey) }
                com.uma.workbench.agent.ActiveModeBridge.publish(mode)
            },
            currentMode = currentMode,
            onDismiss = { viewModel.selectGroup(null) }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            title = { Text("伙伴与群聊") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("伙伴 ${profiles.size} · 群聊 ${groups.size}")
                        Row {
                            TextButton(onClick = { creatingProfile = true }) { Text("新伙伴") }
                            TextButton(onClick = { creatingGroup = true }, enabled = profiles.isNotEmpty()) { Text("新群聊") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("自动日记", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Row {
                            TextButton(onClick = { viewModel.generateDiaryNow() }) { Text("定时生成", fontSize = 12.sp) }
                            TextButton(onClick = { viewModel.triggerDiaryGeneration() }) { Text("立即生成全部", fontSize = 12.sp) }
                        }
                    }
                    LazyColumn {
                        items(profiles, key = { it.id }) { profile ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${profile.name} · ${if (profile.enabled) "启用" else "停用"}",
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { viewModel.triggerDiaryGeneration(profile.id) }) {
                                    Text("生成日记", fontSize = 12.sp)
                                }
                            }
                        }
                        items(groups, key = { it.id }) { group ->
                            Text(
                                "群：${group.name} · 管理员 ${group.managerAgentId}",
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.selectGroup(group.id) }.padding(8.dp)
                            )
                        }
                    }
                }
            }
        )
    }

    if (creatingProfile) {
        CreateAgentProfileDialog(
            onDismiss = { creatingProfile = false },
            onCreate = { name, identity, soul, user ->
                viewModel.createProfile(name, identity, soul, user)
                creatingProfile = false
            }
        )
    }
    if (creatingGroup) {
        CreateAgentGroupDialog(
            profiles = profiles,
            onDismiss = { creatingGroup = false },
            onCreate = { name, managerId, memberIds, policy ->
                viewModel.createGroup(name, managerId, memberIds, policy)
                creatingGroup = false
            }
        )
    }
    if (editingGroupSettings && selectedGroup != null) {
        EditGroupSettingsDialog(
            group = selectedGroup!!,
            allProfiles = profiles,
            onDismiss = { editingGroupSettings = false },
            onSave = { name, description, groupPrompt, managerId, policy ->
                viewModel.updateGroupSettings(selectedGroup!!.id, name, description, groupPrompt, managerId, policy)
                editingGroupSettings = false
            },
            onAddMember = viewModel::addGroupMember,
            onRemoveMember = viewModel::removeGroupMember,
            onDeleteGroup = {
                viewModel.deleteCurrentGroup()
                editingGroupSettings = false
            }
        )
    }
}

@Composable
private fun AgentGroupChatDialog(
    groupName: String,
    messages: List<AgentGroupMessageEntity>,
    generationState: AgentGenerationState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onSettings: () -> Unit,
    authorization: GitHubAuthorizationController,
    approvalGate: com.uma.workbench.agent.UiToolApprovalGate,
    onModeChange: (com.uma.workbench.agent.AgentMode) -> Unit,
    currentMode: com.uma.workbench.agent.AgentMode,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var showingAuthorization by remember { mutableStateOf(false) }
    var pendingModeTransition by remember { mutableStateOf<com.uma.workbench.agent.ModeTransition?>(null) }
    val isGenerating = generationState is AgentGenerationState.Generating
    pendingModeTransition?.let { transition ->
        val warning = transition.warningMessage()
        if (warning != null) {
            AlertDialog(
                onDismissRequest = { pendingModeTransition = null },
                confirmButton = {
                    Button(onClick = {
                        onModeChange(transition.to)
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
            onModeChange(transition.to)
            pendingModeTransition = null
        }
    }
    if (showingAuthorization) {
        GitHubAuthorizationDialog(
            controller = authorization,
            onIssued = { token ->
                showingAuthorization = false
                onSend(
                    "[GitHub授权] 已发放贡献流一次性令牌：$token " +
                        "（10分钟内有效；fork/分支/PR 各一次，文件提交最多8次，PR 创建后整张令牌自动作废）。" +
                        "Agent：将此令牌作为 github_contribute_fork / github_contribute_branch / github_contribute_write / github_contribute_pr 的 confirmationId 参数。"
                )
            },
            onDismiss = { showingAuthorization = false }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(groupName, modifier = Modifier.weight(1f))
                if (isGenerating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("生成中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                var modeMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    Text(currentMode.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp)
                    IconButton(onClick = { modeMenuExpanded = true }, modifier = Modifier.size(28.dp)) {
                        Text("🎛", fontSize = 16.sp)
                    }
                    DropdownMenu(expanded = modeMenuExpanded, onDismissRequest = { modeMenuExpanded = false }) {
                        com.uma.workbench.agent.AgentMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text("${mode.label}（${mode.description.take(20)}）") },
                                onClick = {
                                    modeMenuExpanded = false
                                    val transition = com.uma.workbench.agent.ModeTransition(currentMode, mode)
                                    if (transition.requiresConfirmation) {
                                        pendingModeTransition = transition
                                    } else {
                                        onModeChange(mode)
                                    }
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = { showingAuthorization = true }, modifier = Modifier.size(28.dp)) {
                    Text("🔑", fontSize = 16.sp)
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(28.dp)) {
                    Text("⚙", fontSize = 18.sp)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // Generation status bar
                if (generationState is AgentGenerationState.Generating) {
                    GenerationStatusBar(
                        agentNames = generationState.agentNames,
                        onCancel = onCancel,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    if (messages.isEmpty()) item { Text("暂无群消息") }
                    items(messages, key = { it.id }) { message ->
                        GroupMessageItem(
                            message = message,
                            onRetry = onRetry
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("发送群消息") },
                        modifier = Modifier.weight(1f),
                        enabled = !isGenerating
                    )
                    Button(
                        onClick = { onSend(input); input = "" },
                        enabled = input.isNotBlank() && !isGenerating
                    ) { Text("发送") }
                }
            }
        }
    )
}

@Composable
private fun GenerationStatusBar(
    agentNames: Map<String, String>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                agentNames.values.joinToString("、") + " 正在回复...",
                fontSize = 13.sp
            )
        }
        TextButton(onClick = onCancel) { Text("停止") }
    }
}

@Composable
private fun GroupMessageItem(
    message: AgentGroupMessageEntity,
    onRetry: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Main message content
        Row(verticalAlignment = Alignment.Top) {
            if (message.status == "RUNNING") {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).padding(top = 4.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "${message.senderType}: ${message.content}",
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                // Metadata line
                val metaParts = buildList {
                    if (message.model != null && message.model!!.isNotBlank()) add("模型: ${message.model}")
                    if (message.roundsCount > 0) add("${message.roundsCount}轮")
                    if (message.usageJson != null) {
                        val usage = parseUsageSummary(message.usageJson)
                        if (usage.isNotBlank()) add(usage)
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        metaParts.joinToString(" · "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
        // Tool calls display
        if (!message.toolCallsJson.isNullOrBlank()) {
            var expanded by remember { mutableStateOf(false) }
            val toolCalls = parseToolCalls(message.toolCallsJson)
            if (toolCalls.isNotEmpty()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp)
                ) {
                    Text(
                        (if (expanded) "▼ " else "▶ ") + "工具调用 " + toolCalls.size + " 次",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
                if (expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 2.dp, end = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(8.dp)
                    ) {
                        for (i in toolCalls.indices) {
                            val tc = toolCalls[i]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    (i + 1).toString() + ". ",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    tc.tool,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (tc.status == "ok") "✅" else "❌",
                                    fontSize = 11.sp
                                )
                            }
                            if (tc.args.isNotBlank()) {
                                Text(
                                    tc.args,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        // Status-specific UI
        when (message.status) {
            "FAILED" -> {
                if (!message.errorMessage.isNullOrBlank()) {
                    Text(
                        "❌ ${message.errorMessage}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 2.dp)
                    )
                }
                Row(modifier = Modifier.padding(start = 20.dp, top = 4.dp)) {
                    TextButton(onClick = { onRetry(message.id) }) { Text("重试") }
                }
            }
            "CANCELLED" -> {
                Text(
                    "⚠️ 已取消",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp)
                )
            }
            "COMPLETED" -> {
                // Could show a subtle checkmark or nothing
            }
        }
    }
}

private fun parseUsageSummary(usageJson: String?): String {
    if (usageJson == null) return ""
    return try {
        val regex = Regex("\"total\":(\\d+)")
        val match = regex.find(usageJson)
        if (match != null) "${match.groupValues[1]} tokens" else ""
    } catch (_: Exception) {
        ""
    }
}

private data class ToolCallInfo(val tool: String, val status: String, val args: String)

private fun parseToolCalls(toolCallsJson: String?): List<ToolCallInfo> {
    if (toolCallsJson.isNullOrBlank()) return emptyList()
    return try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val arr = json.parseToJsonElement(toolCallsJson).jsonArray
        arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val tool = obj["tool"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val args = obj["args"]?.jsonPrimitive?.contentOrNull?.take(150) ?: ""
            ToolCallInfo(tool, status, args)
        }
    } catch (_: Exception) {
        emptyList()
    }
}

@Composable
private fun CreateAgentProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var identity by remember { mutableStateOf("你是一个工作区分析伙伴。") }
    var soul by remember { mutableStateOf("直接、基于证据。") }
    var user by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onCreate(name, identity, soul, user.ifBlank { null }) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建伙伴") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(identity, { identity = it }, label = { Text("身份") })
                OutlinedTextField(soul, { soul = it }, label = { Text("人格") })
                OutlinedTextField(user, { user = it }, label = { Text("服务对象，可选") })
            }
        }
    )
}

@Composable
private fun CreateAgentGroupDialog(
    profiles: List<AgentProfileEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, String, List<String>, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var managerId by remember { mutableStateOf(profiles.first().id) }
    var selectedIds by remember { mutableStateOf(setOf(profiles.first().id)) }
    var policy by remember { mutableStateOf(AgentGroupPolicy.MANAGER_SELECTS) }
    var policyExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onCreate(name, managerId, selectedIds.toList(), policy) }, enabled = name.isNotBlank() && managerId in selectedIds) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("新建群聊") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("群名称") }, singleLine = true)
                Text("选择成员")
                profiles.forEach { profile ->
                    Row {
                        Checkbox(
                            checked = profile.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + profile.id else selectedIds - profile.id
                                if (profile.id == managerId && !checked) managerId = selectedIds.firstOrNull() ?: ""
                            }
                        )
                        Text(profile.name, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Row {
                    Text("发言策略：$policy", modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { policyExpanded = true }) { Text("选择") }
                    DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                        listOf(AgentGroupPolicy.MANAGER_SELECTS, AgentGroupPolicy.FREE_SPEAKING, AgentGroupPolicy.USER_DIRECTED).forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { policy = option; policyExpanded = false })
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun EditGroupSettingsDialog(
    group: com.uma.workbench.agent.AgentGroupEntity,
    allProfiles: List<AgentProfileEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?, String, String) -> Unit,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onDeleteGroup: () -> Unit
) {
    var name by remember { mutableStateOf(group.name) }
    var description by remember { mutableStateOf(group.description ?: "") }
    var groupPrompt by remember { mutableStateOf(group.groupPrompt ?: "") }
    var managerId by remember { mutableStateOf(group.managerAgentId) }
    var policy by remember { mutableStateOf(group.turnPolicy) }
    var policyExpanded by remember { mutableStateOf(false) }
    var managerExpanded by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                Button(
                    onClick = onDeleteGroup,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } },
            title = { Text("删除群聊") },
            text = { Text("确定要删除群聊「${group.name}」吗？此操作不可撤销。") }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("删除群聊", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    onClick = { onSave(name, description.ifBlank { null }, groupPrompt.ifBlank { null }, managerId, policy) },
                    enabled = name.isNotBlank() && managerId.isNotBlank()
                ) { Text("保存") }
            }
        },
        title = { Text("群设置") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("群名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("群描述（可选）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = groupPrompt,
                        onValueChange = { groupPrompt = it },
                        label = { Text("群 Prompt（可选）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("管理员：", modifier = Modifier.padding(end = 4.dp))
                        Box {
                            TextButton(onClick = { managerExpanded = true }) {
                                val managerName = allProfiles.firstOrNull { it.id == managerId }?.name ?: managerId
                                Text(managerName)
                            }
                            DropdownMenu(expanded = managerExpanded, onDismissRequest = { managerExpanded = false }) {
                                allProfiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.name) },
                                        onClick = { managerId = profile.id; managerExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("发言策略：$policy", modifier = Modifier.padding(end = 4.dp))
                        Box {
                            TextButton(onClick = { policyExpanded = true }) { Text("选择") }
                            DropdownMenu(expanded = policyExpanded, onDismissRequest = { policyExpanded = false }) {
                                listOf(
                                    com.uma.workbench.agent.AgentGroupPolicy.MANAGER_SELECTS,
                                    com.uma.workbench.agent.AgentGroupPolicy.FREE_SPEAKING,
                                    com.uma.workbench.agent.AgentGroupPolicy.USER_DIRECTED
                                ).forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { policy = option; policyExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("群成员", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showAddMember = true }) { Text("+ 添加") }
                    }
                }
                // Members are passed via group data; we show them from allProfiles
                // In a real app, we'd query members separately; here we use the group's known members
                item {
                    // Member list is handled via the parent's groupMembers flow
                    // For simplicity, show all profiles and mark current members
                    Text("（成员管理请在主界面操作，或使用上方添加按钮）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        }
    )

    if (showAddMember) {
        AddMemberDialog(
            allProfiles = allProfiles,
            onDismiss = { showAddMember = false },
            onAdd = { agentId ->
                onAddMember(agentId)
                showAddMember = false
            }
        )
    }
}

@Composable
private fun AddMemberDialog(
    allProfiles: List<AgentProfileEntity>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加成员") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (allProfiles.isEmpty()) {
                    Text("暂无可用伙伴")
                } else {
                    allProfiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(profile.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(profile.name, modifier = Modifier.weight(1f))
                            Text("添加", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    )
}


/** GitHub 远程操作授权控制器：包一层 store 供 Compose 使用。 */
class GitHubAuthorizationController(private val store: GitHubConfirmationStore) {
    fun issueContributionFlowToken(description: String): String =
        store.issue(
            operations = GitHubConfirmationStore.CONTRIBUTION_FLOW,
            description = description,
            ttlMillis = GitHubConfirmationStore.DEFAULT_TTL_MILLIS
        )

    fun listActive(): List<GitHubConfirmationStore.ConfirmationToken> = store.listActive()

    fun revoke(id: String): Boolean = store.revoke(id)
}

@Composable
private fun GitHubAuthorizationDialog(
    controller: GitHubAuthorizationController,
    onIssued: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var refresh by remember { mutableStateOf(0) }
    val tokens = remember(refresh) { controller.listActive() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val token = controller.issueContributionFlowToken("群聊会话发放")
                onIssued(token)
            }) { Text("授权完整贡献流") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("GitHub 远程操作授权") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "发放一次性令牌后，令牌会作为消息发进群聊，Agent 用它作为 github_contribute_* 工具的 confirmationId。" +
                        "有效期 10 分钟；fork/分支/PR 各限一次，文件提交最多 8 次；PR 创建成功后整张令牌立即作废。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                if (tokens.isEmpty()) {
                    Text("当前没有有效令牌", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                } else {
                    Text("有效令牌 ${tokens.size} 张", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(tokens, key = { it.id }) { token ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        token.id,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "剩余 ${token.remainingUsesPublic} 次 · ${token.operations.joinToString("/") { it.name.lowercase() }}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                                TextButton(onClick = { controller.revoke(token.id); refresh++ }) {
                                    Text("撤销", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ToolApprovalDialog(approvalGate: com.uma.workbench.agent.UiToolApprovalGate) {
    val pending = approvalGate.pending.collectAsStateWithLifecycle().value
    val current = pending.firstOrNull() ?: return
    var customReason by remember(current.request.callId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {
            approvalGate.respond(current.request.callId, approved = false, reason = "用户在 UI 关闭审批对话框")
        },
        confirmButton = {
            Button(
                onClick = {
                    approvalGate.respond(
                        current.request.callId,
                        approved = true,
                        reason = "用户批准"
                    )
                }
            ) { Text("批准") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    approvalGate.respond(
                        current.request.callId,
                        approved = false,
                        reason = if (customReason.isNotBlank()) customReason else "用户拒绝"
                    )
                    customReason = ""
                }
            ) { Text("拒绝") }
        },
        title = { Text("工具执行审批 · ${current.request.riskLevel.name}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("工具：${current.request.toolName}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text("参数：", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp)
                Text(
                    current.request.argumentsJson.take(800),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Text(current.request.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customReason,
                    onValueChange = { customReason = it },
                    label = { Text("拒绝理由（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
