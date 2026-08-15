package com.uma.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.protocol.*
import com.uma.workbench.ui.theme.WorkbenchColors

@Composable
fun ProtocolHistoryPanel(vm: MainViewModel) {
    val records by vm.protocolHistory.collectAsStateWithLifecycle()
    val loadState by vm.protocolHistoryLoadState.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedProtocolHistoryIds.collectAsStateWithLifecycle()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val diff = vm.protocolHistoryDiff()

    Column(Modifier.fillMaxWidth().height(430.dp).padding(8.dp)) {
        Row {
            Text("持久化协议历史（${records.size}）", color = WorkbenchColors.textPrimary)
            Spacer(Modifier.weight(1f))
            Text(loadState.toString(), color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { vm.reloadProtocolHistory() }) { Text("刷新") }
            TextButton(enabled = selectedIds.size == 2, onClick = { showDiff = true }) { Text("完整 Diff") }
            TextButton(onClick = { vm.clearProtocolHistorySelection(); showDiff = false }) { Text("清除选择") }
        }
        if (records.isEmpty()) Text("暂无持久化协议记录", color = WorkbenchColors.textMuted)
        LazyColumn(Modifier.fillMaxSize()) {
            items(records, key = { it.id }) { record ->
                val selected = record.id in selectedIds
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(selected, { vm.toggleProtocolHistorySelection(record.id) })
                    TextButton(onClick = { expandedId = if (expandedId == record.id) null else record.id; showDiff = false }) {
                        Text("${record.timestamp}  ${record.endpoint}  ${record.protocolCode ?: record.httpStatus ?: "无响应"}")
                    }
                }
                if (expandedId == record.id) {
                    val complete = ProtocolHistoryPresentation.detail(vm.protocolHistoryDetail(record))
                    Row { TextButton(onClick = { clipboard.setText(AnnotatedString(complete)) }) { Text("复制完整详情") } }
                    Text(complete, color = WorkbenchColors.textPrimary, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp))
                }
            }
            if (showDiff && diff != null) {
                item {
                    val completeDiff = ProtocolHistoryPresentation.diff(diff)
                    Row { Text("完整请求/响应 Diff", color = WorkbenchColors.accent); Spacer(Modifier.weight(1f)); TextButton(onClick = { clipboard.setText(AnnotatedString(completeDiff)) }) { Text("复制完整 Diff") } }
                    Text(completeDiff, color = WorkbenchColors.textPrimary, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp))
                }
            }
        }
    }
}
