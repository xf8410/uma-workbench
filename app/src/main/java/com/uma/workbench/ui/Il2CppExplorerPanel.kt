package com.uma.workbench.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uma.workbench.hlpatch.Il2CppExplorerPresentation
import com.uma.workbench.hlpatch.Il2CppExplorerState
import com.uma.workbench.ui.theme.WorkbenchColors

/** Real local hlpatch IL2CPP explorer. Results remain complete, horizontally scrollable and copyable. */
@Composable
fun Il2CppExplorerPanel(state: Il2CppExplorerState, onSearch: (String) -> Unit, onFields: (String) -> Unit, onMethods: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val completeText = state.result?.let(Il2CppExplorerPresentation::render)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("IL2CPP 类、字段与方法", color = WorkbenchColors.accent, style = MaterialTheme.typography.labelMedium)
        Row {
            OutlinedTextField(query, { query = it }, label = { Text("类名或搜索词（完整）") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { onSearch(query) }, enabled = !state.running) { Text("搜类") }
            Button(onClick = { onFields(query) }, enabled = !state.running) { Text("字段") }
            Button(onClick = { onMethods(query) }, enabled = !state.running) { Text("方法") }
        }
        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (completeText != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(if (state.result.succeeded) "查询完成" else "查询失败；完整诊断已保留", color = WorkbenchColors.textMuted)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(completeText)) }) { Text("复制完整结果") }
            }
            Text(completeText, color = WorkbenchColors.textPrimary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).horizontalScroll(rememberScrollState()))
        }
    }
}
