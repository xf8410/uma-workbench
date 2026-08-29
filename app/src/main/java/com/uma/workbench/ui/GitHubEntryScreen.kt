package com.uma.workbench.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.ui.theme.WorkbenchColors

private enum class GitHubLoginMode { TOKEN, DEVICE_FLOW }

/**
 * GitHub 登录入口：令牌（PAT）为默认方式——绝大多数用户持有的就是
 * classic / fine-grained PAT，粘贴即用；OAuth Device Flow 需要自建
 * OAuth App 的 Client ID，作为次选折叠保留。
 */
@Composable
fun GitHubEntryScreen(viewModel: GitHubViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.account != null) {
        GitHubScreen(viewModel)
        return
    }

    var mode by remember { mutableStateOf(GitHubLoginMode.TOKEN) }
    var token by remember { mutableStateOf("") }
    val context = LocalContext.current
    val code = state.deviceCode
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GitHub 登录", style = MaterialTheme.typography.headlineSmall, color = WorkbenchColors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == GitHubLoginMode.TOKEN,
                onClick = { mode = GitHubLoginMode.TOKEN },
                label = { Text("访问令牌") }
            )
            FilterChip(
                selected = mode == GitHubLoginMode.DEVICE_FLOW,
                onClick = { mode = GitHubLoginMode.DEVICE_FLOW },
                label = { Text("OAuth 设备流") }
            )
        }
        when (mode) {
            GitHubLoginMode.TOKEN -> {
                Text(
                    "粘贴 GitHub 个人访问令牌（classic 或 fine-grained 均可）。令牌仅加密保存在本机。",
                    color = WorkbenchColors.textSecondary
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("个人访问令牌（ghp_ / github_pat_ 开头）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.login(token) },
                    enabled = !state.loading && token.isNotBlank()
                ) {
                    Text("登录")
                }
            }
            GitHubLoginMode.DEVICE_FLOW -> {
                Text(
                    "需要自建 OAuth App 的 Client ID（公开标识，不是密钥）。访问令牌仅加密保存在本机。",
                    color = WorkbenchColors.textSecondary
                )
                OutlinedTextField(
                    value = state.oauthClientId,
                    onValueChange = viewModel::setOAuthClientId,
                    label = { Text("OAuth App Client ID") },
                    singleLine = true,
                    enabled = code == null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (code == null) {
                    Button(
                        onClick = viewModel::startDeviceFlow,
                        enabled = !state.loading && state.oauthClientId.isNotBlank()
                    ) {
                        Text("开始设备授权")
                    }
                } else {
                    Text("设备验证码：${code.userCode}", style = MaterialTheme.typography.headlineMedium, color = WorkbenchColors.accent)
                    Text("请在 GitHub 授权页面输入验证码。应用正在等待授权结果。", color = WorkbenchColors.textSecondary)
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
                    }) {
                        Text("打开 GitHub 授权页面")
                    }
                    TextButton(onClick = viewModel::cancelDeviceFlow) { Text("取消") }
                }
            }
        }
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
