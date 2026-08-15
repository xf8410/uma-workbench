package com.uma.workbench.ui.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uma.workbench.ui.theme.WorkbenchColors

/** 十六进制查看器：偏移 | 十六进制 | ASCII */
@Composable
fun HexViewer(
    bytes: ByteArray,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val pageSize = 16
    val lines = remember(bytes) {
        bytes.toList().chunked(pageSize).mapIndexed { lineIdx, chunk ->
            val offset = lineIdx * pageSize
            val hex = chunk.joinToString(" ") { "%02X".format(it) }
            val ascii = chunk.joinToString("") { if (it in 32..126) it.toChar().toString() else "." }
            HexLine(offset, hex, ascii)
        }
    }

    Column(modifier.background(WorkbenchColors.bg).fillMaxSize().verticalScroll(scrollState)) {
        // 表头
        Row(
            Modifier.fillMaxWidth().background(WorkbenchColors.bgSecondary)
                .horizontalScroll(horizontalScroll)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Offset", Modifier.width(80.dp), style = headerStyle, color = WorkbenchColors.textMuted)
            Text("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F", Modifier.width(480.dp), style = headerStyle, color = WorkbenchColors.textMuted)
            Text("ASCII", style = headerStyle, color = WorkbenchColors.textMuted)
        }
        // 数据行
        lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(horizontalScroll)
                    .padding(horizontal = 8.dp, vertical = 1.dp)
            ) {
                Text("%08X".format(line.offset), Modifier.width(80.dp), style = monoStyle, color = WorkbenchColors.textMuted)
                Text(line.hex, Modifier.width(480.dp), style = monoStyle, color = WorkbenchColors.info)
                Text(line.ascii, style = monoStyle, color = WorkbenchColors.textPrimary)
            }
        }
    }
}

private data class HexLine(val offset: Int, val hex: String, val ascii: String)

private val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
private val headerStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
