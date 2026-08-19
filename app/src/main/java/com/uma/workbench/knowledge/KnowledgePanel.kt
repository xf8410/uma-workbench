package com.uma.workbench.knowledge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.data.KnowledgeEntryV2Entity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgePanel(vm: KnowledgeViewModel, workspaceId: String) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val selected by vm.selectedEntry.collectAsStateWithLifecycle()
    val refs by vm.evidenceRefs.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var newTopic by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("机制") }
    var newConclusion by remember { mutableStateOf("") }

    LaunchedEffect(workspaceId) { vm.loadEntries(workspaceId) }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("知识库", style = MaterialTheme.typography.titleMedium)
            TextButton({ showCreate = !showCreate }) {
                Icon(Icons.Default.Add, null); Text("新建")
            }
        }

        if (showCreate) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    OutlinedTextField(newTopic, { newTopic = it }, label = { Text("主题") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newCategory, { newCategory = it }, label = { Text("分类") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newConclusion, { newConclusion = it }, label = { Text("结论") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row {
                        Button({
                            if (newTopic.isNotBlank() && newConclusion.isNotBlank()) {
                                vm.createEntry(workspaceId, newTopic, newCategory, newConclusion)
                                newTopic = ""; newConclusion = ""; showCreate = false
                            }
                        }) { Text("保存草稿") }
                        TextButton({ showCreate = false }) { Text("取消") }
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            items(entries, key = { it.id }) { entry ->
                KnowledgeEntryRow(
                    entry = entry,
                    isSelected = selected?.id == entry.id,
                    onClick = { vm.selectEntry(if (selected?.id == entry.id) null else entry) },
                    onPublish = { vm.publishEntry(entry.id) },
                    onDelete = { vm.deleteEntry(entry.id) }
                )
                HorizontalDivider()
            }
        }

        selected?.let { entry ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("详情", style = MaterialTheme.typography.titleSmall)
                    Text("主题: ${entry.topic}")
                    Text("分类: ${entry.category}")
                    Text("结论: ${entry.conclusion}")
                    Text("置信度: ${entry.confidence}")
                    Text("状态: ${entry.status}")
                    if (entry.gameVersion != null) Text("游戏版本: ${entry.gameVersion}")
                    if (entry.openQuestions != null) Text("未解决问题: ${entry.openQuestions}")
                    if (refs.isNotEmpty()) {
                        Text("证据引用 (${refs.size}):", style = MaterialTheme.typography.labelMedium)
                        refs.forEach { ref -> Text("  · ${ref.evidenceArtifactId} (${ref.relevance})", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeEntryRow(
    entry: KnowledgeEntryV2Entity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPublish: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (entry.status) {
        "PUBLISHED" -> MaterialTheme.colorScheme.primary
        "DRAFT" -> MaterialTheme.colorScheme.tertiary
        "SUPERSEDED" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.topic, style = MaterialTheme.typography.bodyMedium)
            Text("${entry.category} · ${entry.confidence} · ${entry.status}", style = MaterialTheme.typography.labelSmall, color = statusColor)
        }
        if (entry.status == "DRAFT") {
            IconButton(onClick = onPublish) { Icon(Icons.Default.Publish, "发布") }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
    }
}
