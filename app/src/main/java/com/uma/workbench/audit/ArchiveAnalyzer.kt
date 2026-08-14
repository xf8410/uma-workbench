package com.uma.workbench.audit

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

object ArchiveAnalyzer {
    fun analyze(input: InputStream): ArchiveAnalysis {
        val source = PushbackInputStream(BufferedInputStream(input), TAR_BLOCK_SIZE)
        val prefix = ByteArray(TAR_BLOCK_SIZE)
        val count = source.readAvailable(prefix)
        if (count > 0) source.unread(prefix, 0, count)
        return when {
            isZip(prefix, count) -> analyzeZip(source)
            isTar(prefix, count) -> analyzeTar(source)
            isSevenZip(prefix, count) -> throw IllegalArgumentException("7z archive parsing is not available yet")
            else -> throw IllegalArgumentException("Unsupported or unrecognized archive format")
        }
    }

    private fun analyzeZip(input: InputStream): ArchiveAnalysis {
        var entries = 0L
        var files = 0L
        var directories = 0L
        var expandedBytes = 0L
        var declaredCompressedBytes = 0L
        var unsafePaths = 0L
        val preview = ArrayList<String>(PREVIEW_SIZE)
        val buffer = ByteArray(BUFFER_SIZE)

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (preview.size < PREVIEW_SIZE) preview += entry.name
                if (isUnsafePath(entry.name)) unsafePaths++
                if (entry.isDirectory) {
                    directories++
                } else {
                    files++
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read > 0) entryBytes += read
                    }
                    expandedBytes += entryBytes
                }
                zip.closeEntry()
                if (entry.compressedSize >= 0) declaredCompressedBytes += entry.compressedSize
            }
        }
        return ArchiveAnalysis(
            archiveFormat = "ZIP",
            entryCount = entries,
            fileCount = files,
            directoryCount = directories,
            expandedBytes = expandedBytes,
            declaredCompressedBytes = declaredCompressedBytes.takeIf { it > 0 },
            unsafePathCount = unsafePaths,
            entryNamePreview = preview,
            warnings = buildList {
                if (unsafePaths > 0) add("Archive contains $unsafePaths path(s) that must not be extracted verbatim")
            }
        )
    }

    private fun analyzeTar(input: InputStream): ArchiveAnalysis {
        var entries = 0L
        var files = 0L
        var directories = 0L
        var expandedBytes = 0L
        var unsafePaths = 0L
        val preview = ArrayList<String>(PREVIEW_SIZE)
        val header = ByteArray(TAR_BLOCK_SIZE)

        while (true) {
            val read = input.readFullyOrEof(header)
            if (read == 0) break
            require(read == TAR_BLOCK_SIZE) { "TAR header is truncated" }
            if (header.all { it == 0.toByte() }) break
            require(validTarChecksum(header)) { "Invalid TAR header checksum at entry ${entries + 1}" }

            val name = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
            val size = parseTarOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            entries++
            if (preview.size < PREVIEW_SIZE) preview += fullName
            if (isUnsafePath(fullName)) unsafePaths++
            if (type == '5') directories++ else {
                files++
                expandedBytes += size
            }
            input.skipFully(size)
            val padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
            input.skipFully(padding)
        }

        return ArchiveAnalysis(
            archiveFormat = "TAR",
            entryCount = entries,
            fileCount = files,
            directoryCount = directories,
            expandedBytes = expandedBytes,
            declaredCompressedBytes = null,
            unsafePathCount = unsafePaths,
            entryNamePreview = preview,
            warnings = buildList {
                if (unsafePaths > 0) add("Archive contains $unsafePaths path(s) that must not be extracted verbatim")
            }
        )
    }

    internal fun isUnsafePath(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || DRIVE_PREFIX.matches(normalized)) return true
        var depth = 0
        for (segment in normalized.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (depth == 0) return true else depth--
                else -> depth++
            }
        }
        return false
    }

    private fun isZip(bytes: ByteArray, count: Int): Boolean = count >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
        ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
            (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) ||
            (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte()))

    private fun isSevenZip(bytes: ByteArray, count: Int): Boolean {
        val magic = byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)
        return count >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)
    }

    private fun isTar(bytes: ByteArray, count: Int): Boolean = count >= TAR_BLOCK_SIZE &&
        (tarString(bytes, 257, 6) == "ustar" || validTarChecksum(bytes))

    private fun validTarChecksum(header: ByteArray): Boolean {
        val expected = runCatching { parseTarOctal(header, 148, 8) }.getOrElse { return false }
        var sum = 0L
        for (index in header.indices) sum += if (index in 148..155) 0x20 else header[index].toInt() and 0xff
        return expected == sum
    }

    private fun parseTarOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        val text = tarString(bytes, offset, length).trim()
        if (text.isEmpty()) return 0
        require(text.all { it in '0'..'7' }) { "Invalid TAR numeric field" }
        return text.toLong(8)
    }

    private fun tarString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: offset + length
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun InputStream.readAvailable(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            if (count > 0) offset += count
        }
        return offset
    }

    private fun InputStream.readFullyOrEof(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            if (count > 0) offset += count
        }
        return offset
    }

    private fun InputStream.skipFully(byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(read >= 0) { "Archive entry data is truncated" }
                remaining -= read
            }
        }
    }

    private const val TAR_BLOCK_SIZE = 512
    private const val BUFFER_SIZE = 64 * 1024
    private const val PREVIEW_SIZE = 20
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:/.*")
}
