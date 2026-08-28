package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.data.EndpointCatalogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阶段18：游戏端点目录面板。
 * 只读展示当前工作区经过的协议端点（path、方法、调用次数、最近时间、状态码）。
 */
@Composable
fun EndpointCatalogPanel(vm: MainViewModel) {
    val entries by vm.endpointCatalog.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("游戏端点目录 (${entries.size})", style = MaterialTheme.typography.labelMedium)
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            if (entries.isEmpty()) {
                Text("暂无可用的端点记录（发送协议请求后会自动归纳）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
                    items(entries, key = { it.id }) { e -> EndpointCatalogRow(e, timeFormat) }
                }
            }
        }
    }
}

@Composable
private fun EndpointCatalogRow(e: EndpointCatalogEntity, timeFormat: SimpleDateFormat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "${e.method ?: "POST"} ${e.path} · ${e.callCount} 次",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            buildString {
                append("最近 ${timeFormat.format(Date(e.lastSeen))}")
                append(" · 状态 ${e.statusCode ?: "—"}")
                e.gameVersion?.let { append(" · 游戏 $it") }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
