package com.uma.workbench.audit

import com.uma.workbench.data.ArchiveEntryEntity
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** ZIP entry indexer. Resumption replays the stream up to the next entry because Content URIs are
 * not guaranteed to be seekable; persisted entryIndex keys make replay idempotent. */
object ArchiveIndexer {
    data class Checkpoint(val nextEntryIndex: Long) {
        fun encode(): String = "archive:zip:$nextEntryIndex"
        companion object {
            fun decode(value: String?): Checkpoint? {
                val prefix = "archive:zip:"
                if (value == null || !value.startsWith(prefix)) return null
                return value.removePrefix(prefix).toLongOrNull()?.takeIf { it >= 0 }?.let(::Checkpoint)
            }
        }
    }

    data class Batch(
        val entries: List<ArchiveEntryEntity>,
        val checkpoint: Checkpoint?,
        val complete: Boolean,
        val scannedEntries: Long,
        val expandedBytes: Long
    )

    fun readZipBatch(
        sourceId: String,
        openInput: () -> InputStream,
        checkpoint: Checkpoint? = null,
        maxEntries: Int = DEFAULT_BATCH_ENTRIES
    ): Batch {
        require(maxEntries > 0)
        val resumeAt = checkpoint?.nextEntryIndex ?: 0
        val output = ArrayList<ArchiveEntryEntity>(maxEntries)
        var index = 0L
        var expanded = 0L
        var hasMore = false
        val buffer = ByteArray(BUFFER_SIZE)

        ZipInputStream(BufferedInputStream(openInput())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (index < resumeAt) {
                    drain(zip, buffer)
                    zip.closeEntry()
                    index++
                    continue
                }
                if (output.size == maxEntries) {
                    hasMore = true
                    break
                }
                val actualBytes = if (entry.isDirectory) 0L else drain(zip, buffer)
                expanded += actualBytes
                output += ArchiveEntryEntity(
                    sourceId = sourceId,
                    entryIndex = index,
                    path = entry.name,
                    directory = entry.isDirectory,
                    uncompressedBytes = actualBytes,
                    compressedBytes = entry.compressedSize.takeIf { it >= 0 },
                    crc32 = entry.crc.takeIf { it >= 0 },
                    unsafePath = ArchiveAnalyzer.isUnsafePath(entry.name),
                    modifiedAt = entry.time.takeIf { it >= 0 },
                    type = "ZIP"
                )
                zip.closeEntry()
                index++
            }
        }
        val next = resumeAt + output.size
        return Batch(output, if (hasMore) Checkpoint(next) else null, !hasMore, next, expanded)
    }

    private fun drain(input: InputStream, buffer: ByteArray): Long {
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            if (count > 0) total += count
        }
    }

    const val DEFAULT_BATCH_ENTRIES = 200
    private const val BUFFER_SIZE = 64 * 1024
}
