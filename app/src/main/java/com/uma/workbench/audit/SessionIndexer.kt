package com.uma.workbench.audit

import com.uma.workbench.data.SessionRecordEntity
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Lossless line-oriented session indexer. Raw lines are retained unchanged; only bounded top-level
 * scalar fields are inspected for timeline/type metadata. Resumption replays to line index because
 * Content URIs are not guaranteed to be seekable. */
object SessionIndexer {
    data class Checkpoint(val nextRecordIndex: Long) {
        fun encode(): String = "session:jsonl:$nextRecordIndex"
        companion object {
            fun decode(value: String?): Checkpoint? {
                val prefix = "session:jsonl:"
                if (value == null || !value.startsWith(prefix)) return null
                return value.removePrefix(prefix).toLongOrNull()?.takeIf { it >= 0 }?.let(::Checkpoint)
            }
        }
    }

    data class Batch(val records: List<SessionRecordEntity>, val checkpoint: Checkpoint?, val complete: Boolean)

    fun readBatch(sourceId: String, openInput: () -> InputStream, checkpoint: Checkpoint? = null, maxRecords: Int = DEFAULT_BATCH_RECORDS): Batch {
        require(maxRecords > 0)
        val resumeAt = checkpoint?.nextRecordIndex ?: 0
        val records = ArrayList<SessionRecordEntity>(maxRecords)
        var index = 0L
        var hasMore = false
        BufferedReader(InputStreamReader(openInput(), StandardCharsets.UTF_8), BUFFER_SIZE).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (index++ < resumeAt) continue
                if (records.size == maxRecords) { hasMore = true; break }
                val scalars = parseTopLevelScalars(line)
                val timestamp = TIMESTAMP_KEYS.firstNotNullOfOrNull { key -> scalars[key]?.let(::parseTimestamp) }
                val type = TYPE_KEYS.firstNotNullOfOrNull { scalars[it]?.take(MAX_TYPE_LENGTH) } ?: "UNKNOWN"
                records += SessionRecordEntity(sourceId, index - 1, line, timestamp, type, scalars.size, scalars.isEmpty() && line.isNotBlank())
            }
        }
        val next = resumeAt + records.size
        return Batch(records, if (hasMore) Checkpoint(next) else null, !hasMore)
    }

    internal fun parseTopLevelScalars(line: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = line.indexOf('{')
        if (index < 0) return result
        index++
        while (index < line.length && result.size < MAX_FIELDS) {
            index = skipWhitespaceAndCommas(line, index)
            if (index >= line.length || line[index] == '}') break
            val key = parseJsonString(line, index) ?: return emptyMap()
            index = key.second
            index = skipWhitespace(line, index)
            if (index >= line.length || line[index] != ':') return emptyMap()
            index = skipWhitespace(line, index + 1)
            val value = when {
                index >= line.length -> return emptyMap()
                line[index] == '"' -> parseJsonString(line, index)?.let { it.first to it.second }
                line[index] == '{' || line[index] == '[' -> null
                else -> {
                    val end = line.indexOfAny(charArrayOf(',', '}'), index).let { if (it < 0) line.length else it }
                    line.substring(index, end).trim().take(MAX_SCALAR_LENGTH) to end
                }
            }
            if (value != null) { result[key.first] = value.first; index = value.second }
            else index = skipNested(line, index)
        }
        return result
    }

    private fun parseTimestamp(value: String): Long? = value.toLongOrNull()?.let { if (it in 1..9_999_999_999L) it * 1000 else it }
        ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun parseJsonString(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length || text[start] != '"') return null
        val output = StringBuilder(); var i = start + 1
        while (i < text.length) {
            val c = text[i++] 
            if (c == '"') return output.toString().take(MAX_SCALAR_LENGTH) to i
            if (c == '\\') {
                if (i >= text.length) return null
                val escaped = text[i++]
                output.append(when (escaped) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'; '"' -> '"'; '\\' -> '\\'; '/' -> '/'; else -> escaped })
            } else output.append(c)
        }
        return null
    }

    private fun skipNested(text: String, start: Int): Int {
        val open = text[start]; val close = if (open == '{') '}' else ']'
        var depth = 0; var quoted = false; var escaped = false; var i = start
        while (i < text.length) {
            val c = text[i++]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else if (c == '"') quoted = true else if (c == open) depth++ else if (c == close && --depth == 0) return i
        }
        return text.length
    }

    private fun skipWhitespace(text: String, start: Int): Int { var i = start; while (i < text.length && text[i].isWhitespace()) i++; return i }
    private fun skipWhitespaceAndCommas(text: String, start: Int): Int { var i = start; while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++; return i }

    const val DEFAULT_BATCH_RECORDS = 500
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_FIELDS = 128
    private const val MAX_SCALAR_LENGTH = 4096
    private const val MAX_TYPE_LENGTH = 128
    private val TIMESTAMP_KEYS = listOf("timestamp", "time", "createdAt", "created_at", "ts")
    private val TYPE_KEYS = listOf("type", "event", "kind", "name")
}
