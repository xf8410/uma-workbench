package com.uma.workbench.ui.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uma.workbench.ui.theme.WorkbenchColors

/** 终端风格历史日志查看器 */
data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val source: String,
    val message: String
)

enum class LogLevel { OK, ERR, WARN, INFO, DEBUG }

@Composable
fun TerminalLogViewer(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val c = WorkbenchColors

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) scrollState.animateScrollTo(entries.size * 20)
    }

    Column(modifier.background(c.bg).fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {
        entries.forEach { entry ->
            val levelColor = when (entry.level) {
                LogLevel.OK -> c.success
                LogLevel.ERR -> c.error
                LogLevel.WARN -> c.warning
                LogLevel.INFO -> c.info
                LogLevel.DEBUG -> c.textMuted
            }
            val levelText = when (entry.level) {
                LogLevel.OK -> "OK"
                LogLevel.ERR -> "ERR"
                LogLevel.WARN -> "WARN"
                LogLevel.INFO -> "INFO"
                LogLevel.DEBUG -> "DBG"
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(entry.timestamp, Modifier.width(90.dp), style = monoStyle, color = c.textMuted)
                Text("[$levelText]", Modifier.width(50.dp), style = monoStyle, color = levelColor)
                Text(entry.source, Modifier.width(120.dp), style = monoStyle, color = c.syntaxFunction)
                Text(entry.message, style = monoStyle, color = c.textPrimary)
            }
        }
    }
}

private val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
