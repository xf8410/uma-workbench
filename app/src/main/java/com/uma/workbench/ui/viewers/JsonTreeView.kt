package com.uma.workbench.ui.viewers

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uma.workbench.ui.theme.WorkbenchColors
import org.json.JSONArray
import org.json.JSONObject

/** JSON 树形查看器：可展开/折叠节点 */
@Composable
fun JsonTreeView(
    jsonText: String,
    modifier: Modifier = Modifier
) {
    val parsed = remember(jsonText) {
        runCatching { parseJson(jsonText.trim()) }.getOrNull()
    }
    val scrollState = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Box(modifier.background(WorkbenchColors.bg).fillMaxSize().verticalScroll(scrollState).horizontalScroll(horizontalScroll)) {
        if (parsed == null) {
            Text("无效 JSON", color = WorkbenchColors.error, modifier = Modifier.padding(8.dp))
        } else {
            Column(Modifier.padding(8.dp)) {
                JsonNodeView(key = "root", value = parsed, depth = 0, isLast = true)
            }
        }
    }
}

@Composable
private fun JsonNodeView(key: String, value: Any?, depth: Int, isLast: Boolean) {
    val c = WorkbenchColors
    val indent = depth * 16

    when (value) {
        is JSONObject -> {
            var expanded by remember { mutableStateOf(depth < 2) }
            val keys = value.keys()
            val entries = keys.asSequence().toList().map { it to value[it] }

            Row(
                modifier = Modifier.padding(start = indent.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = c.textMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.syntaxProperty)) { append("\"$key\"") }
                        withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(": {") }
                        if (!expanded) withStyle(SpanStyle(color = c.textMuted)) { append("…}" ) }
                        else withStyle(SpanStyle(color = c.textMuted)) { append(" ${entries.size} items") }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            AnimatedVisibility(expanded) {
                Column {
                    entries.forEachIndexed { i, (k, v) ->
                        JsonNodeView(key = k, value = v, depth = depth + 1, isLast = i == entries.lastIndex)
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = c.syntaxPunctuation)) { append("}") }
                            if (!isLast) withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(",") }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = indent.dp)
                    )
                }
            }
        }
        is JSONArray -> {
            var expanded by remember { mutableStateOf(depth < 2) }
            val items = (0 until value.length()).map { value[it] }

            Row(
                modifier = Modifier.padding(start = indent.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = c.textMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.syntaxProperty)) { append("\"$key\"") }
                        withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(": [") }
                        if (!expanded) withStyle(SpanStyle(color = c.textMuted)) { append("…]" ) }
                        else withStyle(SpanStyle(color = c.textMuted)) { append(" ${items.size} items") }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            AnimatedVisibility(expanded) {
                Column {
                    items.forEachIndexed { i, v ->
                        JsonNodeView(key = "[$i]", value = v, depth = depth + 1, isLast = i == items.lastIndex)
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = c.syntaxPunctuation)) { append("]") }
                            if (!isLast) withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(",") }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = indent.dp)
                    )
                }
            }
        }
        else -> {
            val valueStr = when (value) {
                is String -> "\"$value\""
                is Boolean -> value.toString()
                is Number -> value.toString()
                null -> "null"
                else -> value.toString()
            }
            val valueColor = when (value) {
                is String -> c.syntaxString
                is Boolean -> c.syntaxKeyword
                is Number -> c.syntaxNumber
                null -> c.syntaxKeyword
                else -> c.textPrimary
            }
            Text(
                buildAnnotatedString {
                    if (key != "root" && !key.startsWith("[")) {
                        withStyle(SpanStyle(color = c.syntaxProperty)) { append("\"$key\"") }
                        withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(": ") }
                    } else if (key.startsWith("[")) {
                        withStyle(SpanStyle(color = c.textMuted)) { append("$key: ") }
                    }
                    withStyle(SpanStyle(color = valueColor)) { append(valueStr) }
                    if (!isLast) withStyle(SpanStyle(color = c.syntaxPunctuation)) { append(",") }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = (indent + 16).dp)
            )
        }
    }
}

private fun parseJson(text: String): Any? {
    val trimmed = text.trim()
    return when {
        trimmed.startsWith("{") -> JSONObject(trimmed)
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> trimmed
    }
}
