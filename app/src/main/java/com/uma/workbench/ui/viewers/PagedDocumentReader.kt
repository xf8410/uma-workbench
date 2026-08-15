package com.uma.workbench.ui.viewers

import java.io.InputStream

/** One exact raw-byte window. Paging changes memory use, never the document's processing scope. */
data class DocumentPage(
    val offset: Long,
    val bytes: ByteArray,
    val totalBytes: Long?,
    val endReached: Boolean
) {
    val nextOffset: Long get() = offset + bytes.size
}

/**
 * Reopens the source for each page and skips to an exact byte offset. No full-file read, file-size
 * rejection, or trailing-byte discard is performed. Content URIs that cannot seek remain usable.
 */
class PagedDocumentReader(
    private val open: () -> InputStream,
    private val size: () -> Long?,
    val pageBytes: Int = 64 * 1024
) {
    init { require(pageBytes > 0) { "pageBytes must be positive" } }

    fun read(offset: Long): DocumentPage {
        require(offset >= 0) { "offset must not be negative" }
        val buffer = ByteArray(pageBytes)
        var count = 0
        open().use { input ->
            skipExactly(input, offset)
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    buffer[count++] = single.toByte()
                } else count += read
            }
        }
        val total = size()
        val bytes = if (count == buffer.size) buffer else buffer.copyOf(count)
        return DocumentPage(offset, bytes, total, bytes.size < pageBytes || (total != null && offset + bytes.size >= total))
    }

    private fun skipExactly(input: InputStream, target: Long) {
        var remaining = target
        val discard = ByteArray(16 * 1024)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read < 0) throw IllegalArgumentException("offset $target exceeds document length")
                if (read > 0) remaining -= read
            }
        }
    }
}

enum class DocumentPageMode { TEXT, HEX }

object DocumentPageRenderer {
    fun modeFor(name: String): DocumentPageMode {
        val lower = name.lowercase()
        return if (lower.endsWith(".so") || lower.endsWith(".apk") || lower.endsWith(".zip") || lower.endsWith(".dat") || lower.endsWith(".db") || lower.endsWith(".sqlite")) DocumentPageMode.HEX else DocumentPageMode.TEXT
    }

    fun text(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
}
