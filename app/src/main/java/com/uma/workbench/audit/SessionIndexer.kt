package com.uma.workbench.audit

import com.uma.workbench.data.SessionFieldEntity
import com.uma.workbench.data.SessionRecordEntity
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant

/** JSONL indexer. Every raw line, scalar value and complete field path is persisted unchanged. */
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

    data class Batch(
        val records: List<SessionRecordEntity>,
        val fields: List<SessionFieldEntity>,
        val checkpoint: Checkpoint?,
        val complete: Boolean
    )

    internal data class Scalar(val path: String, val value: String, val type: String)

    fun readBatch(
        sourceId: String,
        openInput: () -> InputStream,
        checkpoint: Checkpoint? = null,
        maxRecords: Int = DEFAULT_BATCH_RECORDS
    ): Batch {
        require(maxRecords > 0)
        val resumeAt = checkpoint?.nextRecordIndex ?: 0
        val records = ArrayList<SessionRecordEntity>(maxRecords)
        val fields = ArrayList<SessionFieldEntity>()
        var index = 0L
        var hasMore = false

        BufferedReader(InputStreamReader(openInput(), StandardCharsets.UTF_8), BUFFER_SIZE).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (index++ < resumeAt) continue
                if (records.size == maxRecords) {
                    hasMore = true
                    break
                }
                val scalars = parseScalars(line)
                val byPath = scalars.associateBy { it.path }
                val timestamp = TIMESTAMP_PATHS.firstNotNullOfOrNull { key ->
                    byPath[key]?.value?.let(::parseTimestamp)
                }
                val type = TYPE_PATHS.firstNotNullOfOrNull { byPath[it]?.value } ?: "UNKNOWN"
                val recordIndex = index - 1
                records += SessionRecordEntity(
                    sourceId = sourceId,
                    recordIndex = recordIndex,
                    rawText = line,
                    timestampMillis = timestamp,
                    recordType = type,
                    fieldCount = scalars.size,
                    malformed = scalars.isEmpty() && line.isNotBlank()
                )
                fields += scalars.map {
                    SessionFieldEntity(
                        sourceId = sourceId,
                        recordIndex = recordIndex,
                        fieldPath = it.path,
                        normalizedValue = it.value,
                        valueType = it.type,
                        truncated = false
                    )
                }
            }
        }

        val next = resumeAt + records.size
        return Batch(records, fields, if (hasMore) Checkpoint(next) else null, !hasMore)
    }

    internal fun parseTopLevelScalars(line: String): Map<String, String> =
        parseScalars(line)
            .filter { '.' !in it.path && '[' !in it.path }
            .associate { it.path to it.value }

    internal fun parseScalars(line: String): List<Scalar> {
        val parser = JsonScalarParser(line)
        return if (parser.parse()) parser.values else emptyList()
    }

    private fun parseTimestamp(value: String): Long? =
        value.toLongOrNull()?.let { if (it in 1..9_999_999_999L) it * 1000 else it }
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private class JsonScalarParser(private val text: String) {
        val values = ArrayList<Scalar>()
        private var cursor = 0

        fun parse(): Boolean {
            skipWhitespace()
            if (!parseObject("")) return false
            skipWhitespace()
            return cursor == text.length
        }

        private fun parseObject(prefix: String): Boolean {
            if (!consume('{')) return false
            skipWhitespace()
            if (consume('}')) return true
            while (cursor < text.length) {
                val key = parseString() ?: return false
                skipWhitespace()
                if (!consume(':')) return false
                skipWhitespace()
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                if (!parseValue(path)) return false
                skipWhitespace()
                if (consume('}')) return true
                if (!consume(',')) return false
                skipWhitespace()
            }
            return false
        }

        private fun parseArray(prefix: String): Boolean {
            if (!consume('[')) return false
            skipWhitespace()
            if (consume(']')) return true
            var index = 0L
            while (cursor < text.length) {
                if (!parseValue("$prefix[$index]")) return false
                index++
                skipWhitespace()
                if (consume(']')) return true
                if (!consume(',')) return false
                skipWhitespace()
            }
            return false
        }

        private fun parseValue(path: String): Boolean = when (text.getOrNull(cursor)) {
            '{' -> parseObject(path)
            '[' -> parseArray(path)
            '"' -> parseString()?.let { add(path, it, "STRING"); true } ?: false
            't' -> parseLiteral("true", path, "BOOLEAN")
            'f' -> parseLiteral("false", path, "BOOLEAN")
            'n' -> parseLiteral("null", path, "NULL")
            else -> parseNumber(path)
        }

        private fun parseNumber(path: String): Boolean {
            val start = cursor
            while (cursor < text.length && text[cursor] !in charArrayOf(',', '}', ']', ' ', '\t', '\r', '\n')) cursor++
            val raw = text.substring(start, cursor)
            if (raw.isEmpty() || raw.toDoubleOrNull() == null) return false
            add(path, raw, "NUMBER")
            return true
        }

        private fun parseLiteral(expected: String, path: String, type: String): Boolean {
            if (!text.startsWith(expected, cursor)) return false
            cursor += expected.length
            add(path, expected, type)
            return true
        }

        private fun add(path: String, value: String, type: String) {
            values += Scalar(path, value, type)
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val output = StringBuilder()
            while (cursor < text.length) {
                val value = text[cursor++]
                if (value == '"') return output.toString()
                if (value == '\\') {
                    if (cursor >= text.length) return null
                    when (val escaped = text[cursor++]) {
                        'n' -> output.append('\n')
                        'r' -> output.append('\r')
                        't' -> output.append('\t')
                        'b' -> output.append('\b')
                        'f' -> output.append('\u000c')
                        '"' -> output.append('"')
                        '\\' -> output.append('\\')
                        '/' -> output.append('/')
                        'u' -> {
                            if (cursor + 4 > text.length) return null
                            val code = text.substring(cursor, cursor + 4).toIntOrNull(16) ?: return null
                            output.append(code.toChar())
                            cursor += 4
                        }
                        else -> return null
                    }
                } else {
                    output.append(value)
                }
            }
            return null
        }

        private fun consume(expected: Char): Boolean {
            if (text.getOrNull(cursor) != expected) return false
            cursor++
            return true
        }

        private fun skipWhitespace() {
            while (text.getOrNull(cursor)?.isWhitespace() == true) cursor++
        }
    }

    const val DEFAULT_BATCH_RECORDS = 500
    private const val BUFFER_SIZE = 64 * 1024
    private val TIMESTAMP_PATHS = listOf(
        "timestamp", "time", "createdAt", "created_at", "ts",
        "metadata.timestamp", "context.timestamp", "event.timestamp"
    )
    private val TYPE_PATHS = listOf("type", "event", "kind", "name", "metadata.type", "event.type")
}
