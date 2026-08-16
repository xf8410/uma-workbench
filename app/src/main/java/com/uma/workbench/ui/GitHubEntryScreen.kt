package com.uma.workbench.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.ui.theme.WorkbenchColors

/** Chooses OAuth Device Flow before entering the repository workspace. */
@Composable
fun GitHubEntryScreen(viewModel: GitHubViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.account != null) {
        GitHubScreen(viewModel)
        return
    }

    val context = LocalContext.current
    val code = state.deviceCode
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GitHub 登录", style = MaterialTheme.typography.headlineSmall, color = WorkbenchColors.textPrimary)
        Text(
            "使用 GitHub OAuth Device Flow。Client ID 是 OAuth App 的公开标识，不是密钥；访问令牌仅加密保存在本机。",
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
                Text("使用 GitHub 登录")
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
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "如果没有 OAuth App Client ID，仍可返回旧版 PAT 登录页；后续会把备用入口整合到本页。",
            color = WorkbenchColors.textMuted
        )
    }
}
