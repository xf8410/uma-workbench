package com.uma.workbench.audit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveIndexerTest {
    @Test fun indexesZipInIdempotentEntryBatches() {
        val bytes = zipOf("one.txt" to "1", "folder/two.txt" to "22", "../unsafe.txt" to "333")
        val first = ArchiveIndexer.readZipBatch("source", { ByteArrayInputStream(bytes) }, maxEntries = 2)
        assertFalse(first.complete)
        assertEquals(listOf(0L, 1L), first.entries.map { it.entryIndex })
        assertEquals(2L, first.checkpoint?.nextEntryIndex)
        val second = ArchiveIndexer.readZipBatch("source", { ByteArrayInputStream(bytes) }, first.checkpoint, maxEntries = 2)
        assertTrue(second.complete)
        assertEquals(listOf(2L), second.entries.map { it.entryIndex })
        assertTrue(second.entries.single().unsafePath)
    }

    @Test fun indexesTarInResumableBatches() {
        val bytes = tarOf("one.txt" to "1", "folder/two.txt" to "22", "../../unsafe.txt" to "333")
        assertEquals(ArchiveIndexer.Format.TAR, ArchiveIndexer.detectFormat(ByteArrayInputStream(bytes)))
        val first = ArchiveIndexer.readTarBatch("source", { ByteArrayInputStream(bytes) }, maxEntries = 2)
        assertFalse(first.complete)
        assertEquals(ArchiveIndexer.Format.TAR, first.checkpoint?.format)
        assertEquals(listOf("one.txt", "folder/two.txt"), first.entries.map { it.path })
        val second = ArchiveIndexer.readTarBatch("source", { ByteArrayInputStream(bytes) }, first.checkpoint, maxEntries = 2)
        assertTrue(second.complete)
        assertNull(second.checkpoint)
        assertEquals(3L, second.entries.single().uncompressedBytes)
        assertTrue(second.entries.single().unsafePath)
    }

    @Test fun checkpointsRoundTripWithFormat() {
        assertEquals(ArchiveIndexer.Checkpoint(400, ArchiveIndexer.Format.ZIP), ArchiveIndexer.Checkpoint.decode("archive:zip:400"))
        assertEquals(ArchiveIndexer.Checkpoint(200, ArchiveIndexer.Format.TAR), ArchiveIndexer.Checkpoint.decode("archive:tar:200"))
        assertNull(ArchiveIndexer.Checkpoint.decode("archive:7z:1"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }

    private fun tarOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        entries.forEach { (name, value) ->
            val data = value.toByteArray()
            val header = ByteArray(512)
            name.toByteArray().copyInto(header, endIndex = minOf(name.toByteArray().size, 100))
            writeOctal(header, 100, 8, 420)
            writeOctal(header, 108, 8, 0)
            writeOctal(header, 116, 8, 0)
            writeOctal(header, 124, 12, data.size.toLong())
            writeOctal(header, 136, 12, 1)
            for (i in 148..155) header[i] = 0x20
            header[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(header, 257)
            val checksum = header.sumOf { it.toInt() and 0xff }.toLong()
            writeOctal(header, 148, 8, checksum)
            output.write(header); output.write(data)
            repeat((512 - data.size % 512) % 512) { output.write(0) }
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = value.toString(8).padStart(length - 1, '0').takeLast(length - 1)
        digits.toByteArray().copyInto(target, offset)
        target[offset + length - 1] = 0
    }
}
