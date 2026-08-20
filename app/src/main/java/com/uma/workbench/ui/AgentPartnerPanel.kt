package com.uma.workbench.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

@Composable
fun AgentPartnerPanel(
    viewModel: AgentPartnerViewModel,
    workspaceId: String,
    onDismiss: () -> Unit
) {
    viewModel.setWorkspace(workspaceId)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val messages by viewModel.groupMessages.collectAsStateWithLifecycle()
    val generationState by viewModel.generationState.collectAsStateWithLifecycle()
    var creatingProfile by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }

    if (selectedGroup != null) {
        AgentGroupChatDialog(
            groupName = selectedGroup!!.name,
            messages = messages,
            generationState = generationState,
            onSend = viewModel::sendGroupMessage,
            onCancel = viewModel::cancelGroupGeneration,
            onRetry = viewModel::retryFailedMessage,
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
                    LazyColumn {
                        items(profiles, key = { it.id }) { profile ->
                            Text(
                                "${profile.name} · ${if (profile.enabled) "启用" else "停用"}",
                                modifier = Modifier.padding(4.dp)
                            )
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
}

@Composable
private fun AgentGroupChatDialog(
    groupName: String,
    messages: List<AgentGroupMessageEntity>,
    generationState: AgentGenerationState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val isGenerating = generationState is AgentGenerationState.Generating
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(groupName)
                if (isGenerating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("生成中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
                        toolCalls.forEachIndexed { index, (tool, status, args) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Text(
                                    (index + 1).toString() + ". ",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    tool,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (status == "ok") "✅" else "❌",
                                    fontSize = 11.sp
                                )
                            }
                            if (args.isNotBlank()) {
                                Text(
                                    args,
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
