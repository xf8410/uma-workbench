package com.uma.workbench.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.agent.AgentGroupPolicy
import com.uma.workbench.agent.AgentPartnerViewModel
import com.uma.workbench.agent.AgentProfileEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPartnerPanel(
    viewModel: AgentPartnerViewModel,
    workspaceId: String,
    onDismiss: () -> Unit
) {
    viewModel.setWorkspace(workspaceId)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var creatingProfile by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }

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
                        Text("${profile.name} · ${if (profile.enabled) "启用" else "停用"}", modifier = Modifier.padding(4.dp))
                    }
                    items(groups, key = { it.id }) { group ->
                        Text("群：${group.name} · 管理员 ${group.managerAgentId}", modifier = Modifier.padding(4.dp))
                    }
                }
            }
        }
    )

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

@OptIn(ExperimentalMaterial3Api::class)
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
            Button(
                onClick = { onCreate(name, managerId, selectedIds.toList(), policy) },
                enabled = name.isNotBlank() && managerId in selectedIds
            ) { Text("创建") }
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
                ExposedDropdownMenuBox(
                    expanded = policyExpanded,
                    onExpandedChange = { policyExpanded = it }
                ) {
                    OutlinedTextField(
                        value = policy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("发言策略") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(policyExpanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    androidx.compose.material3.ExposedDropdownMenu(
                        expanded = policyExpanded,
                        onDismissRequest = { policyExpanded = false }
                    ) {
                        listOf(
                            AgentGroupPolicy.MANAGER_SELECTS,
                            AgentGroupPolicy.FREE_SPEAKING,
                            AgentGroupPolicy.USER_DIRECTED
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
    )
}
