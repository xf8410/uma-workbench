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
import androidx.compose.ui.unit.dp
import com.uma.workbench.protocol.SidHealthCheckState
import com.uma.workbench.protocol.SidHealthPresentation
import com.uma.workbench.ui.theme.WorkbenchColors

/** Shows the exact checked SID and complete diagnostic evidence without shortening or masking. */
@Composable
fun SidHealthPanel(state: SidHealthCheckState, onCheck: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Row {
            Button(onClick = onCheck, enabled = !state.running) { Text(if (state.running) "检测中" else "检测 SID 有效性") }
            if (state.checkedSid.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(state.checkedSid)) }) { Text("复制本次完整 SID") }
            }
        }
        if (state.checkedSid.isNotEmpty()) {
            Text("本次检测 SID（完整）", color = WorkbenchColors.accent, style = MaterialTheme.typography.labelSmall)
            Text(
                state.checkedSid,
                color = WorkbenchColors.textPrimary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            )
        }
        Text(SidHealthPresentation.statusText(state), color = WorkbenchColors.textPrimary, style = MaterialTheme.typography.labelSmall)
        state.result?.let { result ->
            Text(SidHealthPresentation.loginChainText(result), color = WorkbenchColors.textSecondary, style = MaterialTheme.typography.labelSmall)
            result.response?.let { response ->
                Text("完整响应头：${response.headers}", color = WorkbenchColors.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                Text("原始响应体：${response.body}", color = WorkbenchColors.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                response.bodyDecrypted?.let { Text("解密响应体：$it", color = WorkbenchColors.textSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
