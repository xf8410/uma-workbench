package com.uma.workbench.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uma.workbench.data.ProjectEntity
import com.uma.workbench.data.RecentFileEntity
import com.uma.workbench.data.WorkspaceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ProjectTreePanel(
    workspace: WorkspaceEntity,
    projects: List<ProjectEntity>,
    recentFiles: List<RecentFileEntity>,
    onOpenFile: (String, String) -> Unit,
    onAddProject: (String, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddProject by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    Surface(modifier = modifier, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回工作区列表") }
                Text(workspace.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Divider(Modifier.padding(vertical = 4.dp))

            Text("项目", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                items(projects, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    TextButton(onClick = { showAddProject = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加项目", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (recentFiles.isNotEmpty()) {
                Divider(Modifier.padding(vertical = 4.dp))
                Text("最近文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.heightIn(max = 200.dp)) {
                    items(recentFiles, key = { it.id }) { f ->
                        Row(Modifier.fillMaxWidth().padding(4.dp).clickable { onOpenFile(f.uri, f.name) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showAddProject) {
        AlertDialog(
            onDismissRequest = { showAddProject = false; newProjectName = "" },
            title = { Text("添加项目") },
            text = { OutlinedTextField(value = newProjectName, onValueChange = { newProjectName = it }, label = { Text("项目名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (newProjectName.isNotBlank()) { onAddProject(newProjectName, null); newProjectName = ""; showAddProject = false } }) { Text("添加") } },
            dismissButton = { TextButton(onClick = { showAddProject = false; newProjectName = "" }) { Text("取消") } }
        )
    }
}
