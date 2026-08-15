package com.uma.workbench.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WorkspaceSearchDialog(vm: AiChatViewModel, onDismiss: () -> Unit) {
    val page by vm.searchPage.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val loadingAttachment by vm.loadingAttachment.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(page?.query.orEmpty()) }
    var caseSensitive by remember { mutableStateOf(page?.caseSensitive ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索工作区并附加结果") },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("字面量搜索词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(caseSensitive, { caseSensitive = it })
                        Text("区分大小写")
                    }
                    Button(
                        onClick = { vm.searchWorkspace(query, caseSensitive) },
                        enabled = query.isNotBlank() && !searching
                    ) { Text(if (searching) "搜索中" else "搜索") }
                }
                page?.let { result ->
                    Text(
                        "匹配 ${result.totalMatches} 个 · 当前偏移 ${result.offset} · 扫描 ${result.scannedDocuments}/${result.availableDocuments} 个文档",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (!result.isCompleteDocumentScan) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text("搜索范围不完整", color = MaterialTheme.colorScheme.onErrorContainer)
                                if (result.documentsExcludedByLimit > 0) Text("文档数量上限排除了 ${result.documentsExcludedByLimit} 个文档", style = MaterialTheme.typography.labelSmall)
                                if (result.partiallyScannedUris.isNotEmpty()) Text("部分扫描：\n${result.partiallyScannedUris.joinToString("\n")}", style = MaterialTheme.typography.labelSmall)
                                result.failures.forEach { failure ->
                                    Text("读取失败：${failure.uri}\n${failure.completeError}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(result.matches, key = { "${it.uri}:${it.lineNumber}:${it.columnNumber}" }) { match ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${match.title} L${match.lineNumber}:C${match.columnNumber}", style = MaterialTheme.typography.labelMedium)
                                    Text(match.uri, style = MaterialTheme.typography.labelSmall)
                                    Text(match.completeLine, fontFamily = FontFamily.Monospace)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { vm.attachSearchMatch(match) }, enabled = !loadingAttachment) { Text("附加完整行") }
                                    }
                                }
                            }
                        }
                    }
                    if (result.nextOffset != null) {
                        OutlinedButton(onClick = vm::nextSearchPage, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                            Text("下一页（从 ${result.nextOffset} 开始）")
                        }
                    }
                } ?: Box(Modifier.weight(1f).fillMaxWidth()) {
                    Text("搜索当前工作区的活动文件、最近文件和已导入来源。结果不会自动发送，只有点击“附加完整行”后才进入模型上下文。")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
