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
        assertNull(second.checkpoint)
        assertEquals(listOf(2L), second.entries.map { it.entryIndex })
        assertTrue(second.entries.single().unsafePath)
        assertEquals(3L, second.entries.single().uncompressedBytes)
    }

    @Test fun checkpointRoundTrips() {
        val checkpoint = ArchiveIndexer.Checkpoint(400)
        assertEquals(checkpoint, ArchiveIndexer.Checkpoint.decode(checkpoint.encode()))
        assertNull(ArchiveIndexer.Checkpoint.decode("archive:tar:400"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
