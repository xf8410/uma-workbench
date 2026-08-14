package com.uma.workbench.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uma.workbench.data.OpenTabEntity
import com.uma.workbench.ui.MainViewModel

@Composable fun ViewerPanel(
    tabs: List<OpenTabEntity>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    vm: MainViewModel,
    modifier: Modifier = Modifier
) {
    val activeTab = tabs.find { it.id == activeTabId }
    val fileContent by vm.fileContent.collectAsStateWithLifecycle()

    Column(modifier) {
        // 标签栏
        if (tabs.isNotEmpty()) {
            ScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0), edgePadding = 0.dp) {
                tabs.forEachIndexed { index, tab ->
                    Tab(selected = tab.id == activeTabId, onClick = { onSelectTab(tab.id) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                IconButton(onClick = { onCloseTab(tab.id) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    )
                }
            }
        }

        // 内容区
        if (activeTab == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("打开文件或选择项目开始查看", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FileViewer(activeTab, fileContent, vm)
        }
    }
}

@Composable private fun FileViewer(tab: OpenTabEntity, content: String?, vm: MainViewModel) {
    var viewMode by remember { mutableStateOf("text") }

    Column(Modifier.fillMaxSize()) {
        // 查看模式切换
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = viewMode == "text", onClick = { viewMode = "text" }, label = { Text("文本") })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = viewMode == "json", onClick = { viewMode = "json" }, label = { Text("JSON") })
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = viewMode == "hex", onClick = { viewMode = "hex" }, label = { Text("十六进制") })
            Spacer(Modifier.weight(1f))
            Text(tab.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Divider()

        when (viewMode) {
            "text" -> TextContent(content ?: "加载中…")
            "json" -> JsonContent(content ?: "{}")
            "hex" -> HexContent(content ?: "")
        }
    }
}

@Composable private fun TextContent(text: String) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun JsonContent(text: String) {
    val formatted = remember(text) { runCatching { prettyPrintJson(text) }.getOrDefault(text) }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item { Text(formatted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun HexContent(text: String) {
    val hex = remember(text) {
        text.toByteArray().take(4096).joinToString("") { "%02x".format(it) }.chunked(32).mapIndexed { i, line ->
            "%08x  %s".format(i * 16, line.chunked(2).joinToString(" "))
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(hex) { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun prettyPrintJson(text: String): String {
    val sb = StringBuilder()
    var indent = 0
    var inString = false
    for (c in text) {
        when {
            inString -> { sb.append(c); if (c == '"') inString = false }
            c == '"' -> { sb.append(c); inString = true }
            c == '{' || c == '[' -> { sb.append(c).append('\n'); indent++; sb.append("  ".repeat(indent)) }
            c == '}' || c == ']' -> { sb.append('\n'); indent = (indent - 1).coerceAtLeast(0); sb.append("  ".repeat(indent)).append(c) }
            c == ',' -> { sb.append(c).append('\n').append("  ".repeat(indent)) }
            c == ':' -> sb.append(": ")
            !c.isWhitespace() -> sb.append(c)
        }
    }
    return sb.toString()
}
