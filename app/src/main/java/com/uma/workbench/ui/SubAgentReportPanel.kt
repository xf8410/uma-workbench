package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uma.workbench.agent.SubAgentReportPresentation

@Composable
fun SubAgentReportPanel(completeToolContent: String, modifier: Modifier = Modifier): Boolean {
    val reports = remember(completeToolContent) { SubAgentReportPresentation.parse(completeToolContent) } ?: return false
    Column(modifier.fillMaxWidth()) {
        Text("子 Agent 报告 · ${reports.size} 个任务", style = MaterialTheme.typography.labelMedium)
        reports.forEach { report ->
            var expanded by remember(report.taskId, report.requestId) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth().padding(top = 4.dp).clickable { expanded = !expanded }) {
                Column(Modifier.padding(8.dp)) {
                    val status = when (report.status) { "success" -> "成功"; "failure" -> "失败"; else -> report.status }
                    Text("${report.taskId} · $status", style = MaterialTheme.typography.labelMedium)
                    Text(
                        buildList {
                            report.totalTokens?.let { add("Token $it") }
                            add("证据 ${report.evidenceCount}")
                            if (report.completeEvidenceCount != report.evidenceCount) add("完整 ${report.completeEvidenceCount}")
                            report.model?.let { add(it) }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (expanded) {
                        report.answer?.let { Text(it, Modifier.padding(top = 6.dp)) }
                        report.error?.let { Text(it.take(4_096), Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error) }
                        report.requestId?.let { Text("请求 $it", style = MaterialTheme.typography.labelSmall) }
                    } else Text("点击展开", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    return true
}
