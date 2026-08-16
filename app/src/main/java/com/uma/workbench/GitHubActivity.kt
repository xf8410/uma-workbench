package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.ui.GitHubEntryScreen
import com.uma.workbench.ui.GitHubViewModel
import com.uma.workbench.ui.theme.WorkbenchTheme

/** Separate GitHub entry point while the main three-pane navigation is still being expanded. */
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
