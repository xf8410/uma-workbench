package com.uma.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uma.workbench.ui.theme.WorkbenchColors

@Composable
fun ImportIndexPanel(rows: List<ImportStatusRow>, onRetry: (String) -> Unit) {
    var expandedSourceId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("APK / SO / Session 导入与索引", color = WorkbenchColors.textSecondary)
        if (rows.isEmpty()) Text("尚未导入文件；使用左侧“导入并索引”启动系统文件选择器。", color = WorkbenchColors.textMuted)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { expandedSourceId = if (expandedSourceId == row.sourceId) null else row.sourceId }) {
                    Text("${row.name} · ${row.kind} · ${row.status} ${row.progress}%")
                }
                if (row.status == "FAILED" || row.status == "RETRY_WAIT") {
                    TextButton(onClick = { onRetry(row.sourceId) }) { Text("恢复索引") }
                }
            }
            if (expandedSourceId == row.sourceId) {
                Text(
                    ImportPresentation.detail(row),
                    color = WorkbenchColors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)
                )
            }
        }
    }
}
