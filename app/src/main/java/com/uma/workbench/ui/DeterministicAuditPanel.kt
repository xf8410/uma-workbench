package com.uma.workbench.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 确定性审计面板：零模型调用的来源分析。
 * 选择当前工作区已导入的来源 → DeterministicAuditEngine 逐来源确定性解析 → 摘要与证据落库。
 */
@Composable
fun DeterministicAuditPanel(vm: DeterministicAuditViewModel, workspaceId: String) {
    LaunchedEffect(workspaceId) { vm.bindWorkspace(workspaceId) }
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selected by vm.selectedIds.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("确定性审计", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::selectAllVisible, enabled = !running && sources.isNotEmpty()) { Text("全选") }
            TextButton(onClick = vm::clearSelection, enabled = !running && selected.isNotEmpty()) { Text("清空") }
            Button(onClick = vm::runAudit, enabled = !running && selected.isNotEmpty()) {
                Text(if (running) "审计中…" else "开始审计")
            }
        }
        Text(
            "零模型调用：来源由确定性分析器解析（ELF / SQLite / IL2CPP / 压缩包），证据写入 evidence 表。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        if (error != null) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        }
        if (sources.isEmpty()) {
            Text(
                "当前工作区暂无审计来源。请先在「代码」页导入文件，或通过 GitHub 克隆来源。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sources, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text("${s.kind}${s.fileSize?.let { " · ${"%,d".format(it)} 字节" } ?: ""}${s.duplicateOf?.let { " · 重复" } ?: ""}") },
                        leadingContent = {
                            Checkbox(checked = s.id in selected, onCheckedChange = { vm.toggleSource(s.id) }, enabled = !running)
                        }
                    )
                }
            }
        }
        if (summary != null) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("审计摘要", style = MaterialTheme.typography.titleSmall)
                    Text(summary ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
