package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.ui.GitHubEntryScreen
import com.uma.workbench.ui.GitHubViewModel
import com.uma.workbench.ui.theme.WorkbenchTheme

/**
 * GitHub 仓库浏览入口。
 *
 * 已不再注册为独立启动应用（AndroidManifest 中不含 MAIN/LAUNCHER），
 * 而是由主程序顶栏的"GitHub 仓库"按钮显式启动，作为内置功能使用。
 */
class GitHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorkbenchTheme {
                GitHubEntryScreen(viewModel<GitHubViewModel>())
            }
        }
    }
}
