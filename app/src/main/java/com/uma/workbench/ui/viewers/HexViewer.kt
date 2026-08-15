package com.uma.workbench.ui.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uma.workbench.ui.theme.WorkbenchColors

/** Raw-byte hexadecimal viewer. The base offset is preserved across document pages. */
@Composable
fun HexViewer(bytes: ByteArray, modifier: Modifier = Modifier, baseOffset: Long = 0L) {
    val horizontalScroll = rememberScrollState()
    val lines = remember(bytes, baseOffset) {
        bytes.asIterable().chunked(16).mapIndexed { lineIndex, chunk ->
            val offset = baseOffset + lineIndex * 16L
            val hex = chunk.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            val ascii = chunk.joinToString("") {
                val value = it.toInt() and 0xff
                if (value in 32..126) value.toChar().toString() else "."
            }
            HexLine(offset, hex, ascii)
        }
    }

    LazyColumn(modifier.background(WorkbenchColors.bg).fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().background(WorkbenchColors.bgSecondary).horizontalScroll(horizontalScroll).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Offset", Modifier.width(100.dp), style = headerStyle, color = WorkbenchColors.textMuted)
                Text("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F", Modifier.width(480.dp), style = headerStyle, color = WorkbenchColors.textMuted)
                Text("ASCII", style = headerStyle, color = WorkbenchColors.textMuted)
            }
        }
        items(lines, key = { it.offset }) { line ->
            Row(Modifier.fillMaxWidth().horizontalScroll(horizontalScroll).padding(horizontal = 8.dp, vertical = 1.dp)) {
                Text("%016X".format(line.offset), Modifier.width(100.dp), style = monoStyle, color = WorkbenchColors.textMuted)
                Text(line.hex, Modifier.width(480.dp), style = monoStyle, color = WorkbenchColors.info)
                Text(line.ascii, style = monoStyle, color = WorkbenchColors.textPrimary)
            }
        }
    }
}

private data class HexLine(val offset: Long, val hex: String, val ascii: String)
private val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
private val headerStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
