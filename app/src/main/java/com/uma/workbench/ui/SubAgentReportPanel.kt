package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uma.workbench.agent.AgentToolResultPage
import com.uma.workbench.agent.SubAgentReportPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the complete persisted report for UI only. Nothing loaded here is added to model context. */
@Composable
fun PersistedSubAgentReportPanel(
    resultId: String,
    completeCharacterCount: Int,
    read: (String, Int, Int) -> AgentToolResultPage,
    modifier: Modifier = Modifier
) {
    var content by remember(resultId) { mutableStateOf<String?>(null) }
    var error by remember(resultId) { mutableStateOf<String?>(null) }
    LaunchedEffect(resultId, completeCharacterCount) {
        runCatching {
            withContext(Dispatchers.IO) {
                val page = read(resultId, 0, completeCharacterCount.coerceAtLeast(1))
                check(page.complete) { "落盘子报告未完整读取：${page.endOffsetExclusive}/${page.totalCharacterCount}" }
                page.content
            }
        }.onSuccess { content = it }.onFailure { error = it.stackTraceToString() }
    }
    when {
        error != null -> Text("读取完整子报告失败\n${error!!.take(4_096)}", modifier, color = MaterialTheme.colorScheme.error)
        content == null -> Text("正在从落盘结果读取完整子报告…", modifier, style = MaterialTheme.typography.labelSmall)
        !SubAgentReportPanel(content!!, modifier) -> Text("落盘内容不是有效的子 Agent 报告", modifier, color = MaterialTheme.colorScheme.error)
    }
}

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
                    Text(buildList {
                        report.totalTokens?.let { add("Token $it") }
                        add("证据 ${report.evidenceCount}")
                        if (report.completeEvidenceCount != report.evidenceCount) add("完整 ${report.completeEvidenceCount}")
                        report.roundsCount?.let { add("轮次 $it") }
                        report.toolCallCount?.let { add("工具 $it") }
                        report.elapsedMillis?.let { add("耗时 ${"%.1f".format(it / 1000.0)}s") }
                        report.model?.let { add(it) }
                    }.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    if (expanded) {
                        report.answer?.let { Text(it, Modifier.padding(top = 6.dp)) }
                        report.error?.let { Text(it, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error) }
                        report.requestId?.let { Text("请求 $it", style = MaterialTheme.typography.labelSmall) }
                    } else Text("点击展开", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    return true
}
