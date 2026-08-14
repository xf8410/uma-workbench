package com.uma.workbench.audit

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface BinaryAnalysis {
    val format: String
    val warnings: List<String>
}

data class ElfAnalysis(
    val is64Bit: Boolean,
    val endian: String,
    val machine: Int,
    val type: Int,
    val entryPoint: Long,
    val programHeaderOffset: Long,
    val sectionHeaderOffset: Long,
    override val warnings: List<String> = emptyList()
) : BinaryAnalysis { override val format: String = "ELF" }

data class SqliteAnalysis(
    val pageSize: Int,
    val pageCount: Long,
    val textEncoding: Int,
    val isWalModeHint: Boolean,
    override val warnings: List<String> = emptyList()
) : BinaryAnalysis { override val format: String = "SQLite" }

object BinaryAnalyzers {
    fun analyzeElf(header: ByteArray): ElfAnalysis {
        require(header.size >= 64) { "ELF header is truncated" }
        require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) { "Not an ELF file" }
        val is64 = header[4].toInt() == 2
        require(header[4].toInt() == 1 || is64) { "Unsupported ELF class" }
        val little = header[5].toInt() == 1
        require(header[5].toInt() == 1 || header[5].toInt() == 2) { "Unsupported ELF endian" }
        val buffer = ByteBuffer.wrap(header).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val type = buffer.getShort(16).toInt() and 0xffff
        val machine = buffer.getShort(18).toInt() and 0xffff
        val entry = if (is64) buffer.getLong(24) else buffer.getInt(24).toLong() and 0xffffffffL
        val phoff = if (is64) buffer.getLong(32) else buffer.getInt(28).toLong() and 0xffffffffL
        val shoff = if (is64) buffer.getLong(40) else buffer.getInt(32).toLong() and 0xffffffffL
        return ElfAnalysis(is64, if (little) "little" else "big", machine, type, entry, phoff, shoff)
    }

    fun analyzeSqliteHeader(header: ByteArray): SqliteAnalysis {
        require(header.size >= 100) { "SQLite header is truncated" }
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        require(header.copyOfRange(0, 16).contentEquals(magic)) { "Not a SQLite 3 database" }
        val pageSize = ((header[16].toInt() and 0xff) shl 8) or (header[17].toInt() and 0xff)
        val normalizedPageSize = if (pageSize == 1) 65536 else pageSize
        require(normalizedPageSize == 0 || normalizedPageSize in 512..32768 || normalizedPageSize == 65536) { "Invalid SQLite page size" }
        val pageCount = uint32(header, 28)
        val textEncoding = uint32(header, 56).toInt()
        val warnings = buildList { if (header[18].toInt() == 2 || header[19].toInt() == 2) add("SQLite database uses WAL or rollback journal mode") }
        return SqliteAnalysis(normalizedPageSize, pageCount, textEncoding, header[18].toInt() == 2, warnings)
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or ((bytes[offset + 1].toLong() and 0xff) shl 16) or ((bytes[offset + 2].toLong() and 0xff) shl 8) or (bytes[offset + 3].toLong() and 0xff)
}
