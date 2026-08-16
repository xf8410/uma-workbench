package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.github.GitContent
import com.uma.workbench.ui.theme.WorkbenchColors

@Composable
fun GitHubScreen(viewModel: GitHubViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = WorkbenchColors.bg) {
        when {
            !state.tokenPresent || state.account == null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("GitHub 登录", style = MaterialTheme.typography.headlineSmall, color = WorkbenchColors.textPrimary)
                Text(
                    "请输入 GitHub Personal Access Token。Token 只会使用 Android Keystore 加密保存在本机。",
                    color = WorkbenchColors.textSecondary
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Personal Access Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { viewModel.login(token) }, enabled = !state.loading && token.isNotBlank()) {
                    Text("验证并登录")
                }
                Text("需要读取私有仓库时，请为细粒度 Token 授予目标仓库 Contents: Read 权限。", color = WorkbenchColors.textMuted)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.loading) CircularProgressIndicator()
            }

            state.file != null -> {
                val file = requireNotNull(state.file)
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = viewModel::closeFile) { Icon(Icons.Default.ArrowBack, "返回目录") }
                        Column(Modifier.weight(1f)) {
                            Text(file.path, color = WorkbenchColors.textPrimary)
                            Text("SHA ${file.sha} · ${file.size} bytes", color = WorkbenchColors.textMuted)
                        }
                    }
                    Text(
                        text = file.content,
                        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                        color = WorkbenchColors.textPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            state.selectedRepository != null -> RepositoryBrowser(state, viewModel)
            else -> RepositoryList(state, viewModel)
        }
    }
}

@Composable
private fun RepositoryList(state: GitHubUiState, viewModel: GitHubViewModel) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GitHub · ${state.account?.login}", style = MaterialTheme.typography.titleLarge, color = WorkbenchColors.textPrimary)
                Text("${state.repositories.size} 个仓库（第 1 页）", color = WorkbenchColors.textMuted)
            }
            TextButton(onClick = viewModel::refreshAccountAndRepositories) { Text("刷新") }
            TextButton(onClick = viewModel::logout) { Text("退出") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) CircularProgressIndicator()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.repositories, key = { it.id }) { repository ->
                Column(
                    Modifier.fillMaxWidth().clickable { viewModel.openRepository(repository) }.padding(10.dp)
                ) {
                    Text("${repository.owner}/${repository.name}", color = WorkbenchColors.textPrimary)
                    Text(
                        "${if (repository.isPrivate) "私有" else "公开"} · ${repository.defaultBranch}${repository.description?.let { " · $it" }.orEmpty()}",
                        color = WorkbenchColors.textMuted,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositoryBrowser(state: GitHubUiState, viewModel: GitHubViewModel) {
    val repository = requireNotNull(state.selectedRepository)
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::closeRepository) { Icon(Icons.Default.ArrowBack, "返回仓库列表") }
            Column(Modifier.weight(1f)) {
                Text("${repository.owner}/${repository.name}", color = WorkbenchColors.textPrimary)
                Text("${state.ref}:${if (state.path.isEmpty()) "/" else state.path}", color = WorkbenchColors.textMuted)
            }
            if (state.path.isNotEmpty()) TextButton(onClick = viewModel::goUp) { Text("上一级") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loading) CircularProgressIndicator()
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.directory, key = { it.path }) { entry -> RepositoryEntry(entry, viewModel) }
        }
    }
}

@Composable
private fun RepositoryEntry(entry: GitContent, viewModel: GitHubViewModel) {
    Row(
        Modifier.fillMaxWidth().clickable {
            if (entry.type == "dir") viewModel.openDirectory(entry.path) else viewModel.openFile(entry.path)
        }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.type == "dir") Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.type == "dir") WorkbenchColors.accent else WorkbenchColors.textSecondary
        )
        Text(entry.path.substringAfterLast('/'), Modifier.padding(start = 8.dp).weight(1f), color = WorkbenchColors.textPrimary)
        if (entry.type != "dir") Text("${entry.size} B", color = WorkbenchColors.textMuted)
    }
}
