package com.uma.workbench.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.uma.workbench.protocol.ProtocolHistoryTransferState
import com.uma.workbench.ui.theme.WorkbenchColors
import kotlinx.coroutines.launch

/** Real Storage Access Framework controls; no synthetic path or server is used. */
@Composable
fun ProtocolHistoryTransferControls(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ProtocolHistoryTransferState()) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        if (uri != null) scope.launch {
            state = ProtocolHistoryTransferState(running = true, operation = "导出", uri = uri.toString())
            state = runCatching { vm.exportProtocolHistoryJsonl(uri) }
                .fold(
                    { count -> ProtocolHistoryTransferState(operation = "导出", uri = uri.toString(), exportedRecords = count) },
                    { error -> ProtocolHistoryTransferState(operation = "导出", uri = uri.toString(), error = error.stackTraceToString()) }
                )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            state = ProtocolHistoryTransferState(running = true, operation = "导入", uri = uri.toString())
            state = runCatching { vm.importProtocolHistoryJsonl(uri) }
                .fold(
                    { result -> ProtocolHistoryTransferState(operation = "导入", uri = uri.toString(), importResult = result) },
                    { error -> ProtocolHistoryTransferState(operation = "导入", uri = uri.toString(), error = error.stackTraceToString()) }
                )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row {
            TextButton(enabled = !state.running, onClick = { importLauncher.launch(arrayOf("application/x-ndjson", "application/json", "text/plain")) }) { Text("从系统文档导入 JSONL") }
            TextButton(enabled = !state.running, onClick = { exportLauncher.launch("uma-protocol-history.jsonl") }) { Text("导出 JSONL 到系统文档") }
            Spacer(Modifier.weight(1f))
            Text(state.summary, color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall)
        }
        state.importResult?.errors?.forEach { error ->
            Text(
                "第 ${error.lineNumber} 行错误：${error.message}\n完整原文：${error.completeLine}",
                color = WorkbenchColors.textPrimary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            )
        }
    }
}
