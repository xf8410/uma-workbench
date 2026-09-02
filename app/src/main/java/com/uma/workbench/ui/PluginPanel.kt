package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.plugin.PluginLifecycleState
import com.uma.workbench.plugin.PluginRegistryRepository
import com.uma.workbench.plugin.RegisteredPlugin
import kotlinx.coroutines.launch

/**
 * 插件注册表面板：安装（SAF 选 manifest JSON）/启用/禁用/卸载。
 *
 * 数据层（PluginRegistryRepository + 7 个验证器）此前完整但零 UI 接入——
 * 本面板补上最后一根线。
 */
@Composable
fun PluginPanel(repository: PluginRegistryRepository, appVersionCode: Int) {
    val plugins by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<RegisteredPlugin?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { scope.launch { message = installFromUri(context, repository, it, appVersionCode) } } }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("插件注册表", style = MaterialTheme.typography.titleLarge)
        Text("URA 式插件生态：manifest 声明 + 权限 + 传输 + 兼容性校验", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ importLauncher.launch(arrayOf("*/*")) }) { Text("安装插件 manifest") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        if (plugins.isEmpty()) {
            Text("未安装任何插件。选择插件 manifest JSON 文件安装。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(plugins, key = { it.manifest.id }) { p ->
                    PluginRow(
                        p,
                        onToggle = {
                            scope.launch {
                                val r = if (p.state == PluginLifecycleState.ENABLED) repository.disable(p.manifest.id) else repository.enable(p.manifest.id)
                                message = when (r) {
                                    is com.uma.workbench.plugin.PluginTransitionResult.Accepted -> "${p.manifest.name}：${r.plugin.state}"
                                    is com.uma.workbench.plugin.PluginTransitionResult.Rejected -> "${p.manifest.name}：${r.reason}"
                                }
                            }
                        },
                        onUninstall = {
                            scope.launch {
                                val r = repository.uninstall(p.manifest.id)
                                message = when (r) {
                                    is com.uma.workbench.plugin.PluginTransitionResult.Accepted -> "已卸载 ${p.manifest.name}"
                                    is com.uma.workbench.plugin.PluginTransitionResult.Rejected -> "${p.manifest.name}：${r.reason}"
                                }
                            }
                        },
                        onDetail = { detail = p }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    detail?.let { p ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton({ detail = null }) { Text("关闭") } },
            title = { Text("${p.manifest.name} · ${p.manifest.version}") },
            text = {
                Column {
                    Text("ID：${p.manifest.id}")
                    Text("描述：${p.manifest.description}")
                    Text("发布者：${p.manifest.publisher.name}")
                    Text("权限：${p.grantedPermissions.ifEmpty { p.manifest.permissions }.joinToString("，").ifEmpty { "无" }}")
                    Text("状态：${p.state}")
                    p.lastError?.let { Text("最后错误：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
            }
        )
    }
}

@Composable
private fun PluginRow(p: RegisteredPlugin, onToggle: () -> Unit, onUninstall: () -> Unit, onDetail: () -> Unit) {
    val enabled = p.state == PluginLifecycleState.ENABLED
    ListItem(
        headlineContent = { Text("${p.manifest.name} · ${p.manifest.version}", modifier = Modifier.clickable { onDetail() }) },
        supportingContent = {
            Column {
                Text(p.manifest.description.ifBlank { "无描述" }, style = MaterialTheme.typography.labelSmall)
                Text("状态 ${p.state}" + (p.lastError?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onToggle) { Icon(if (enabled) Icons.Default.Stop else Icons.Default.PlayArrow, if (enabled) "禁用" else "启用") }
                IconButton(onClick = onUninstall) { Icon(Icons.Default.Delete, "卸载") }
            }
        }
    )
}

private suspend fun installFromUri(context: android.content.Context, repository: PluginRegistryRepository, uri: android.net.Uri, appVersionCode: Int): String {
    return try {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取所选文件")
        }.getOrThrow()
        val manifestJson = String(bytes, Charsets.UTF_8)
        val manifest = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<com.uma.workbench.plugin.PluginManifest>(manifestJson)
        val result = repository.install(manifest, appVersionCode)
        if (result.isValid) "已安装 ${manifest.name} · ${manifest.version}" else "安装被拒：${result.issues.filter{it.severity==com.uma.workbench.plugin.PluginValidationSeverity.ERROR}.joinToString("；"){it.code+" "+it.message}}"
    } catch (e: Exception) {
        "安装失败：${e.message ?: e::class.java.simpleName}"
    }
}

