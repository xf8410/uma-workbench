package com.uma.workbench.audit

import com.uma.workbench.data.ArchiveEntryEntity
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

/**
 * Streaming ZIP/TAR entry indexer. Resumption replays the source up to the next entry because
 * Content URIs are not guaranteed to be seekable. Persisted sourceId + entryIndex keys make
 * replay idempotent and no entry content is extracted to disk.
 */
object ArchiveIndexer {
    enum class Format(val checkpointName: String) { ZIP("zip"), TAR("tar") }

    data class Checkpoint(val nextEntryIndex: Long, val format: Format = Format.ZIP) {
        fun encode(): String = "archive:${format.checkpointName}:$nextEntryIndex"
        companion object {
            fun decode(value: String?): Checkpoint? {
                if (value == null) return null
                val parts = value.split(':', limit = 3)
                if (parts.size != 3 || parts[0] != "archive") return null
                val format = Format.entries.firstOrNull { it.checkpointName == parts[1] } ?: return null
                return parts[2].toLongOrNull()?.takeIf { it >= 0 }?.let { Checkpoint(it, format) }
            }
        }
    }

    data class Batch(val entries: List<ArchiveEntryEntity>, val checkpoint: Checkpoint?, val complete: Boolean, val scannedEntries: Long, val expandedBytes: Long)

    fun detectFormat(input: InputStream): Format {
        val bytes = ByteArray(TAR_BLOCK_SIZE)
        val count = input.use { it.readFullyOrEof(bytes) }
        if (count >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) return Format.ZIP
        if (count == TAR_BLOCK_SIZE && validTarChecksum(bytes)) return Format.TAR
        throw IllegalArgumentException("Unsupported or unrecognized archive format")
    }

    fun readZipBatch(sourceId: String, openInput: () -> InputStream, checkpoint: Checkpoint? = null, maxEntries: Int = DEFAULT_BATCH_ENTRIES): Batch {
        require(maxEntries > 0)
        require(checkpoint == null || checkpoint.format == Format.ZIP) { "Checkpoint format does not match ZIP" }
        val resumeAt = checkpoint?.nextEntryIndex ?: 0
        val output = ArrayList<ArchiveEntryEntity>(maxEntries)
        var index = 0L; var expanded = 0L; var hasMore = false
        val buffer = ByteArray(BUFFER_SIZE)
        ZipInputStream(BufferedInputStream(openInput())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (index < resumeAt) { drain(zip, buffer); zip.closeEntry(); index++; continue }
                if (output.size == maxEntries) { hasMore = true; break }
                val actualBytes = if (entry.isDirectory) 0L else drain(zip, buffer)
                expanded += actualBytes
                output += ArchiveEntryEntity(sourceId, index, entry.name, entry.isDirectory, actualBytes, entry.compressedSize.takeIf { it >= 0 }, entry.crc.takeIf { it >= 0 }, ArchiveAnalyzer.isUnsafePath(entry.name), entry.time.takeIf { it >= 0 }, "ZIP")
                zip.closeEntry(); index++
            }
        }
        val next = resumeAt + output.size
        return Batch(output, if (hasMore) Checkpoint(next, Format.ZIP) else null, !hasMore, next, expanded)
    }

    fun readTarBatch(sourceId: String, openInput: () -> InputStream, checkpoint: Checkpoint? = null, maxEntries: Int = DEFAULT_BATCH_ENTRIES): Batch {
        require(maxEntries > 0)
        require(checkpoint == null || checkpoint.format == Format.TAR) { "Checkpoint format does not match TAR" }
        val resumeAt = checkpoint?.nextEntryIndex ?: 0
        val output = ArrayList<ArchiveEntryEntity>(maxEntries)
        var index = 0L; var expanded = 0L; var hasMore = false
        val header = ByteArray(TAR_BLOCK_SIZE)
        BufferedInputStream(openInput()).use { tar ->
            while (true) {
                val read = tar.readFullyOrEof(header)
                if (read == 0) break
                require(read == TAR_BLOCK_SIZE) { "TAR header is truncated" }
                if (header.all { it == 0.toByte() }) break
                require(validTarChecksum(header)) { "Invalid TAR header checksum at entry ${index + 1}" }
                val name = tarString(header, 0, 100); val prefix = tarString(header, 345, 155)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val size = parseTarOctal(header, 124, 12); val typeFlag = header[156].toInt().toChar()
                val directory = typeFlag == '5' || path.endsWith('/')
                if (index < resumeAt) { tar.skipFully(size + paddingFor(size)); index++; continue }
                if (output.size == maxEntries) { hasMore = true; break }
                output += ArchiveEntryEntity(sourceId, index, path, directory, if (directory) 0 else size, null, null, ArchiveAnalyzer.isUnsafePath(path), parseTarOctal(header, 136, 12).takeIf { it > 0 }?.times(1000), "TAR:${if (typeFlag.code == 0) '0' else typeFlag}")
                if (!directory) expanded += size
                tar.skipFully(size + paddingFor(size)); index++
            }
        }
        val next = resumeAt + output.size
        return Batch(output, if (hasMore) Checkpoint(next, Format.TAR) else null, !hasMore, next, expanded)
    }

    private fun drain(input: InputStream, buffer: ByteArray): Long { var total = 0L; while (true) { val count = input.read(buffer); if (count < 0) return total; if (count > 0) total += count } }
    private fun validTarChecksum(header: ByteArray): Boolean { val expected = runCatching { parseTarOctal(header, 148, 8) }.getOrElse { return false }; var sum = 0L; for (index in header.indices) sum += if (index in 148..155) 0x20 else header[index].toInt() and 0xff; return expected == sum }
    private fun parseTarOctal(bytes: ByteArray, offset: Int, length: Int): Long { val text = tarString(bytes, offset, length).trim(); if (text.isEmpty()) return 0; require(text.all { it in '0'..'7' }) { "Invalid TAR numeric field" }; return text.toLong(8) }
    private fun tarString(bytes: ByteArray, offset: Int, length: Int): String { val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: offset + length; return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8).trim() }
    private fun paddingFor(size: Long): Long = (TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE
    private fun InputStream.readFullyOrEof(output: ByteArray): Int { var offset = 0; while (offset < output.size) { val count = read(output, offset, output.size - offset); if (count < 0) break; if (count > 0) offset += count }; return offset }
    private fun InputStream.skipFully(byteCount: Long) { var remaining = byteCount; val buffer = ByteArray(BUFFER_SIZE); while (remaining > 0) { val skipped = skip(remaining); if (skipped > 0) remaining -= skipped else { val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); require(count >= 0) { "Archive entry data is truncated" }; remaining -= count } } }

    const val DEFAULT_BATCH_ENTRIES = 200
    private const val BUFFER_SIZE = 64 * 1024
    private const val TAR_BLOCK_SIZE = 512
}
