package com.uma.workbench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uma.workbench.ui.GitHubScreen
import com.uma.workbench.ui.GitHubViewModel
import com.uma.workbench.ui.theme.WorkbenchTheme

/** Separate entry point while the main three-pane navigation is still being expanded. */
class GitHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorkbenchTheme {
                GitHubScreen(viewModel<GitHubViewModel>())
            }
        }
    }
}
