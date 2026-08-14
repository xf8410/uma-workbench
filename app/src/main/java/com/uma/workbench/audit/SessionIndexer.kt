package com.uma.workbench.audit

import com.uma.workbench.data.SessionFieldEntity
import com.uma.workbench.data.SessionRecordEntity
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Lossless JSONL indexer with a bounded normalized-field projection. Raw lines remain unchanged. */
object SessionIndexer {
    data class Checkpoint(val nextRecordIndex: Long) {
        fun encode(): String = "session:jsonl:$nextRecordIndex"
        companion object { fun decode(value: String?): Checkpoint? { val p = "session:jsonl:"; if (value == null || !value.startsWith(p)) return null; return value.removePrefix(p).toLongOrNull()?.takeIf { it >= 0 }?.let(::Checkpoint) } }
    }
    data class Batch(val records: List<SessionRecordEntity>, val fields: List<SessionFieldEntity>, val checkpoint: Checkpoint?, val complete: Boolean)
    internal data class Scalar(val path: String, val value: String, val type: String, val truncated: Boolean)

    fun readBatch(sourceId: String, openInput: () -> InputStream, checkpoint: Checkpoint? = null, maxRecords: Int = DEFAULT_BATCH_RECORDS): Batch {
        require(maxRecords > 0)
        val resumeAt = checkpoint?.nextRecordIndex ?: 0
        val records = ArrayList<SessionRecordEntity>(maxRecords)
        val fields = ArrayList<SessionFieldEntity>()
        var index = 0L; var hasMore = false
        BufferedReader(InputStreamReader(openInput(), StandardCharsets.UTF_8), BUFFER_SIZE).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (index++ < resumeAt) continue
                if (records.size == maxRecords) { hasMore = true; break }
                val scalars = parseScalars(line)
                val byPath = scalars.associateBy { it.path }
                val timestamp = TIMESTAMP_PATHS.firstNotNullOfOrNull { key -> byPath[key]?.value?.let(::parseTimestamp) }
                val type = TYPE_PATHS.firstNotNullOfOrNull { byPath[it]?.value?.take(MAX_TYPE_LENGTH) } ?: "UNKNOWN"
                val recordIndex = index - 1
                records += SessionRecordEntity(sourceId, recordIndex, line, timestamp, type, scalars.size, scalars.isEmpty() && line.isNotBlank())
                fields += scalars.map { SessionFieldEntity(sourceId, recordIndex, it.path, it.value, it.type, it.truncated) }
            }
        }
        val next = resumeAt + records.size
        return Batch(records, fields, if (hasMore) Checkpoint(next) else null, !hasMore)
    }

    internal fun parseTopLevelScalars(line: String): Map<String, String> = parseScalars(line).filter { '.' !in it.path && '[' !in it.path }.associate { it.path to it.value }

    internal fun parseScalars(line: String): List<Scalar> {
        val parser = JsonScalarParser(line)
        return if (parser.parse()) parser.values else emptyList()
    }

    private fun parseTimestamp(value: String): Long? = value.toLongOrNull()?.let { if (it in 1..9_999_999_999L) it * 1000 else it } ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private class JsonScalarParser(private val text: String) {
        val values = ArrayList<Scalar>()
        private var i = 0
        fun parse(): Boolean { skipWs(); return parseObject("", 0) && run { skipWs(); i == text.length } }
        private fun parseObject(prefix: String, depth: Int): Boolean {
            if (depth > MAX_DEPTH || !take('{')) return false
            skipWs(); if (take('}')) return true
            while (i < text.length && values.size < MAX_FIELDS) {
                val key = string() ?: return false; skipWs(); if (!take(':')) return false; skipWs()
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                if (!value(path, depth + 1)) return false
                skipWs(); if (take('}')) return true; if (!take(',')) return false; skipWs()
            }
            return skipContainer('}')
        }
        private fun parseArray(prefix: String, depth: Int): Boolean {
            if (depth > MAX_DEPTH || !take('[')) return false
            skipWs(); if (take(']')) return true
            var index = 0
            while (i < text.length && values.size < MAX_FIELDS && index < MAX_ARRAY_ITEMS) {
                if (!value("$prefix[$index]", depth + 1)) return false
                index++; skipWs(); if (take(']')) return true; if (!take(',')) return false; skipWs()
            }
            return skipContainer(']')
        }
        private fun value(path: String, depth: Int): Boolean = when (text.getOrNull(i)) {
            '{' -> parseObject(path, depth)
            '[' -> parseArray(path, depth)
            '"' -> string()?.let { add(path, it, "STRING"); true } ?: false
            't' -> literal("true", path, "BOOLEAN")
            'f' -> literal("false", path, "BOOLEAN")
            'n' -> literal("null", path, "NULL")
            else -> number(path)
        }
        private fun number(path: String): Boolean {
            val start = i
            while (i < text.length && text[i] !in charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')) i++
            val raw = text.substring(start, i)
            if (raw.isEmpty() || raw.toDoubleOrNull() == null) return false
            add(path, raw, "NUMBER"); return true
        }
        private fun literal(expected: String, path: String, type: String): Boolean { if (!text.startsWith(expected, i)) return false; i += expected.length; add(path, expected, type); return true }
        private fun add(path: String, raw: String, type: String) { val truncated = raw.length > MAX_SCALAR_LENGTH; values += Scalar(path.take(MAX_PATH_LENGTH), raw.take(MAX_SCALAR_LENGTH), type, truncated) }
        private fun string(): String? {
            if (!take('"')) return null
            val out = StringBuilder()
            while (i < text.length) {
                val c = text[i++]
                if (c == '"') return out.toString()
                if (c == '\\') {
                    if (i >= text.length) return null
                    val e = text[i++]
                    when (e) {
                        'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); 'b' -> out.append('\b'); 'f' -> out.append('\u000c'); '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                        'u' -> { if (i + 4 > text.length) return null; val code = text.substring(i, i + 4).toIntOrNull(16) ?: return null; out.append(code.toChar()); i += 4 }
                        else -> return null
                    }
                } else out.append(c)
            }
            return null
        }
        private fun skipContainer(close: Char): Boolean { var quoted = false; var escaped = false; var nested = 0; while (i < text.length) { val c = text[i++]; if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false } else if (c == '"') quoted = true else if (c == '{' || c == '[') nested++ else if ((c == '}' || c == ']') && nested-- == 0 && c == close) return true }; return false }
        private fun take(c: Char): Boolean { if (text.getOrNull(i) != c) return false; i++; return true }
        private fun skipWs() { while (text.getOrNull(i)?.isWhitespace() == true) i++ }
    }

    const val DEFAULT_BATCH_RECORDS = 500
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_FIELDS = 128
    private const val MAX_DEPTH = 8
    private const val MAX_ARRAY_ITEMS = 32
    private const val MAX_SCALAR_LENGTH = 4096
    private const val MAX_PATH_LENGTH = 512
    private const val MAX_TYPE_LENGTH = 128
    private val TIMESTAMP_PATHS = listOf("timestamp", "time", "createdAt", "created_at", "ts", "metadata.timestamp", "context.timestamp", "event.timestamp")
    private val TYPE_PATHS = listOf("type", "event", "kind", "name", "metadata.type", "event.type")
}
