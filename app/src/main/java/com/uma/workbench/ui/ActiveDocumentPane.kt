package com.uma.workbench.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.uma.workbench.data.OpenTabEntity
import com.uma.workbench.ui.theme.WorkbenchColors
import com.uma.workbench.ui.viewers.PagedDocumentViewer

/** Active tabs are read from their original Content URI in raw-byte pages. */
@Composable
fun ActiveDocumentPane(openTabs: List<OpenTabEntity>, activeTabId: String?) {
    val active = openTabs.firstOrNull { it.id == activeTabId }
    if (active == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("打开文件开始工作", color = WorkbenchColors.textMuted)
        }
    } else {
        PagedDocumentViewer(active.uri, active.title, Modifier.fillMaxSize())
    }
}
