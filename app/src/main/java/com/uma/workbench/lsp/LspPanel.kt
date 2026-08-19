package com.uma.workbench.lsp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.data.LspDiagnosticEntity
import com.uma.workbench.data.LspServerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LspPanel(vm: LspViewModel, workspaceId: String) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val currentFile by vm.currentFileUri.collectAsStateWithLifecycle()

    LaunchedEffect(workspaceId) {
        vm.ensureBuiltinServers(workspaceId)
        vm.loadServers(workspaceId)
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("语言服务器", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        servers.forEach { server ->
            LspServerRow(server, onToggle = { vm.toggleServer(workspaceId, server.serverId, !server.enabled) }, onRemove = { vm.removeServer(workspaceId, server.serverId) })
            HorizontalDivider()
        }

        if (servers.isEmpty()) {
            Text("尚未配置 LSP 服务器", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(8.dp))

        val errors = diagnostics.count { it.severity == "ERROR" }
        val warnings = diagnostics.count { it.severity == "WARNING" }
        if (diagnostics.isNotEmpty()) {
            Text("诊断 ($errors 错误, $warnings 警告)", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                items(diagnostics, key = { it.id }) { d ->
                    DiagnosticRow(d)
                    HorizontalDivider()
                }
            }
        } else if (currentFile != null) {
            Text("当前文件无诊断信息", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LspServerRow(server: LspServerEntity, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (server.enabled) Icons.Default.Check else Icons.Default.Close,
            null,
            tint = if (server.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(server.displayName, style = MaterialTheme.typography.bodyMedium)
            Text("${server.language} · ${server.command} · ${server.status}", style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = server.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "删除") }
    }
}

@Composable
private fun DiagnosticRow(d: LspDiagnosticEntity) {
    val color = when (d.severity) {
        "ERROR" -> MaterialTheme.colorScheme.error
        "WARNING" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().padding(4.dp)) {
        Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("${d.startLine + 1}:${d.startColumn + 1} ${d.message}", style = MaterialTheme.typography.bodySmall)
            if (d.source != null || d.code != null) {
                Text(listOfNotNull(d.source, d.code).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
