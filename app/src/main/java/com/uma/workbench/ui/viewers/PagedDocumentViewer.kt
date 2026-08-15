package com.uma.workbench.ui.viewers

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uma.workbench.ui.theme.WorkbenchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF-backed page viewer. Only the current byte page is held by the UI. */
@Composable
fun PagedDocumentViewer(uri: String, name: String, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    val reader = remember(uri) {
        PagedDocumentReader(
            open = { resolver.openInputStream(Uri.parse(uri)) ?: error("无法打开文档：$uri") },
            size = { resolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length.takeIf { length -> length >= 0 } } }
        )
    }
    var offset by remember(uri) { mutableLongStateOf(0L) }
    var page by remember(uri) { mutableStateOf<DocumentPage?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    var loading by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(reader, offset) {
        loading = true
        error = null
        runCatching { withContext(Dispatchers.IO) { reader.read(offset) } }
            .onSuccess { page = it }
            .onFailure { error = it.stackTraceToString() }
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(enabled = !loading && offset > 0, onClick = { offset = (offset - reader.pageBytes).coerceAtLeast(0) }) { Text("上一页") }
            TextButton(enabled = !loading && page?.endReached == false, onClick = { offset = page?.nextOffset ?: offset }) { Text("下一页") }
            Text("字节 ${page?.offset ?: offset}..${page?.nextOffset ?: offset} / ${page?.totalBytes?.toString() ?: "未知"}", color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text(if (loading) "读取中" else "每页 ${reader.pageBytes} 字节", color = WorkbenchColors.textMuted, style = MaterialTheme.typography.labelSmall)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp))
        }
        page?.let { current ->
            when (DocumentPageRenderer.modeFor(name)) {
                DocumentPageMode.HEX -> HexViewer(current.bytes, Modifier.weight(1f), current.offset)
                DocumentPageMode.TEXT -> CodeViewer(DocumentPageRenderer.text(current.bytes), languageFor(name), Modifier.weight(1f))
            }
        }
    }
}

private fun languageFor(name: String): String = when {
    name.endsWith(".kt", true) -> "kotlin"
    name.endsWith(".xml", true) -> "xml"
    name.endsWith(".json", true) || name.endsWith(".jsonl", true) -> "json"
    else -> "text"
}
