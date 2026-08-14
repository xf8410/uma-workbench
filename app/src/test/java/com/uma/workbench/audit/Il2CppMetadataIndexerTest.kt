package com.uma.workbench.audit

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppMetadataIndexerTest {
    @Test fun encodesAndDecodesCheckpoint() {
        val value = Il2CppMetadataIndexer.Checkpoint("string", 262144)
        assertEquals(value, Il2CppMetadataIndexer.Checkpoint.decode(value.encode()))
        assertNull(Il2CppMetadataIndexer.Checkpoint.decode("header"))
    }

    @Test fun indexesAStringSectionInResumableBatches() {
        val source = ByteArray(64)
        "alpha\u0000beta\u0000gamma".toByteArray().copyInto(source, destinationOffset = 16)
        val section = Il2CppMetadataSection("string", 16, 16)

        val first = Il2CppMetadataIndexer.readSectionBatch("source", { ByteArrayInputStream(source) }, section, maxBytes = 8)
        assertFalse(first.complete)
        assertEquals(8L, first.checkpoint?.nextOffset)
        assertEquals("alpha", first.fragments.first().text)
        assertTrue(first.fragments.last().continuesToNext)
        assertEquals(64, first.chunk.sha256.length)

        val second = Il2CppMetadataIndexer.readSectionBatch("source", { ByteArrayInputStream(source) }, section, nextOffset = 8, maxBytes = 8)
        assertTrue(second.complete)
        assertNull(second.checkpoint)
        assertTrue(second.fragments.first().continuesFromPrevious)
    }

    @Test fun indexesStructuredSectionWithoutTreatingItAsText() {
        val source = ByteArray(80) { it.toByte() }
        val section = Il2CppMetadataSection("typeDefinitions", 16, 32)
        val batch = Il2CppMetadataIndexer.readSectionBatch("source", { ByteArrayInputStream(source) }, section, maxBytes = 16)
        assertEquals("typeDefinitions", batch.chunk.sectionName)
        assertEquals(0L, batch.chunk.sectionOffset)
        assertEquals(16, batch.chunk.byteCount)
        assertTrue(batch.fragments.isEmpty())
        assertFalse(batch.complete)
    }

    @Test fun rejectsSectionsOutsideKnownSourceLength() {
        val analysis = Il2CppMetadataAnalysis(29, listOf(
            Il2CppMetadataSection("string", 100, 20),
            Il2CppMetadataSection("images", 500, 100)
        ))
        val valid = Il2CppMetadataIndexer.validateSections(analysis, sourceLength = 550)
        assertEquals(listOf("string"), valid.map { it.name })
    }
}
