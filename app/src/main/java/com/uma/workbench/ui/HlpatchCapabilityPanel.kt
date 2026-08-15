package com.uma.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.hlpatch.HlpatchCapabilityClassifier
import com.uma.workbench.hlpatch.HlpatchCapabilityReport
import com.uma.workbench.ui.theme.WorkbenchColors

/** Displays every observed endpoint, complete response body, and complete error without masking. */
@Composable
fun HlpatchCapabilityPanel(report: HlpatchCapabilityReport, onDiscover: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val complete = HlpatchCapabilityClassifier.presentation(report)
    Column(Modifier.fillMaxWidth()) {
        Row {
            Button(enabled = !report.running, onClick = onDiscover) { Text(if (report.running) "检测中" else "检测 hlpatch 能力") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(complete)) }) { Text("复制完整诊断") }
        }
        Text(report.summary, color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.labelSmall)
        if (report.endpoints.isNotEmpty()) {
            Text(complete, color = WorkbenchColors.textSecondary, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
        }
        val explorer: Il2CppExplorerViewModel = viewModel()
        val explorerState = explorer.state.collectAsStateWithLifecycle().value
        Il2CppExplorerPanel(explorerState, explorer::searchClasses, explorer::readFields, explorer::readMethods)
    }
}
