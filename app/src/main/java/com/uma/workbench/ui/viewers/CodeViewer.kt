package com.uma.workbench.ui.viewers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uma.workbench.ui.theme.WorkbenchColors

/** 代码查看器：行号 + 语法高亮 + 等宽字体 */
@Composable
fun CodeViewer(
    text: String,
    language: String = "text",
    modifier: Modifier = Modifier
) {
    val lines = text.split("\n")
    val scrollState = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val highlighted = remember(text, language) { highlightSyntax(text, language) }
    val lineNumbers = remember(lines) { lines.indices.joinToString("\n") { (it + 1).toString() } }

    Row(modifier.background(WorkbenchColors.bg).fillMaxSize()) {
        // 行号列
        Text(
            text = lineNumbers,
            modifier = Modifier
                .background(WorkbenchColors.bgSecondary)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .verticalScroll(scrollState),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = WorkbenchColors.textMuted
            )
        )
        // 代码内容
        Box(modifier = Modifier.weight(1f).verticalScroll(scrollState).horizontalScroll(horizontalScroll)) {
            Text(
                text = highlighted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
            )
        }
    }
}

/** 语法高亮——支持 json, kotlin, xml, text */
fun highlightSyntax(text: String, language: String): AnnotatedString = when (language) {
    "json" -> highlightJson(text)
    "kotlin" -> highlightKotlin(text)
    "xml" -> highlightXml(text)
    else -> AnnotatedString(text)
}

private fun highlightJson(text: String): AnnotatedString = buildAnnotatedString {
    val c = WorkbenchColors
    var i = 0
    while (i < text.length) {
        when (val ch = text[i]) {
            '"' -> {
                val start = i; i++
                while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                i++
                val str = text.substring(start, i)
                val isKey = i < text.length && text[i] == ':'
                withStyle(SpanStyle(color = if (isKey) c.syntaxProperty else c.syntaxString)) { append(str) }
            }
            '{', '}', '[', ']', ',', ':' -> { withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(ch) }; i++ }
            in '0'..'9', '-', '.' -> {
                val start = i
                while (i < text.length && text[i] in '0'..'9' || text[i] in ".-+eE") i++
                withStyle(SpanStyle(color = c.syntaxNumber)) { append(text.substring(start, i)) }
            }
            in ' ', '\t', '\n', '\r' -> { append(ch); i++ }
            else -> {
                val start = i
                while (i < text.length && text[i] !in "\"{},[]: \t\n\r") i++
                val word = text.substring(start, i)
                val color = when (word) {
                    "true", "false", "null" -> c.syntaxKeyword
                    else -> c.textPrimary
                }
                withStyle(SpanStyle(color = color)) { append(word) }
            }
        }
    }
}

private val KOTLIN_KEYWORDS = setOf("fun", "val", "var", "class", "object", "interface", "enum", "import", "package", "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "suspend", "override", "private", "public", "internal", "protected", "open", "sealed", "abstract", "data", "companion", "init", "constructor", "this", "super", "null", "true", "false", "is", "in", "as", "typealias", "inline", "reified", "operator", "infix", "tailrec", "by", "get", "set", "where")

private fun highlightKotlin(text: String): AnnotatedString = buildAnnotatedString {
    val c = WorkbenchColors
    val regex = Regex("(//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|\\b\\d+\\.?\\d*[fFlL]?\\b|\\b(\\w+)\\b|[^\\w\\s])")
    var lastEnd = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > lastEnd) append(text.substring(lastEnd, match.range.first))
        val word = match.value
        val color = when {
            word.startsWith("//") || word.startsWith("/*") -> c.syntaxComment
            word.startsWith("\"") || word.startsWith("'") -> c.syntaxString
            word.matches(Regex("\\d+\\.?\\d*")) -> c.syntaxNumber
            word in KOTLIN_KEYWORDS -> c.syntaxKeyword
            word.first().isUpperCase() -> c.syntaxType
            match.range.first > 0 && text.getOrNull(match.range.first - 1) == '.' -> c.syntaxProperty
            else -> c.textPrimary
        }
        withStyle(SpanStyle(color = color)) { append(word) }
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) append(text.substring(lastEnd))
}

private fun highlightXml(text: String): AnnotatedString = buildAnnotatedString {
    val c = WorkbenchColors
    val regex = Regex("(/\\?[\\w\\-:.]+|\"[^\"]*\"|<!--[^>]*-->|<[!?/]?|>|=|[^<>=]+)")
    for (match in regex.findAll(text)) {
        val word = match.value
        val color = when {
            word.startsWith("<!--") -> c.syntaxComment
            word.startsWith("\"") -> c.syntaxString
            word.startsWith("</") || word.startsWith("<") || word == ">" || word == "/>" || word == "/>" -> c.syntaxPunctuation
            word.matches(Regex("/?\\w[\\w\\-:.]*")) -> c.syntaxType
            word == "=" -> c.syntaxOperator
            else -> c.textPrimary
        }
        withStyle(SpanStyle(color = color)) { append(word) }
    }
}
