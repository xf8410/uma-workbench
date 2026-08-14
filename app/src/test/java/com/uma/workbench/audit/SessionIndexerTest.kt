package com.uma.workbench.audit

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIndexerTest {
    @Test fun preservesRawLinesAndResumes() {
        val source = listOf(
            "{\"type\":\"start\",\"timestamp\":\"2026-08-14T12:00:00Z\",\"payload\":{\"x\":1}}",
            "{\"event\":\"tick\",\"ts\":1723636801}",
            "not-json"
        ).joinToString("\n").toByteArray()
        val first = SessionIndexer.readBatch("s", { ByteArrayInputStream(source) }, maxRecords = 2)
        assertFalse(first.complete)
        assertEquals(2L, first.checkpoint?.nextRecordIndex)
        assertEquals("start", first.records[0].recordType)
        assertEquals("{\"type\":\"start\",\"timestamp\":\"2026-08-14T12:00:00Z\",\"payload\":{\"x\":1}}", first.records[0].rawText)
        assertEquals(1723636801000, first.records[1].timestampMillis)
        val second = SessionIndexer.readBatch("s", { ByteArrayInputStream(source) }, first.checkpoint, maxRecords = 2)
        assertTrue(second.complete)
        assertNull(second.checkpoint)
        assertEquals(2L, second.records.single().recordIndex)
        assertTrue(second.records.single().malformed)
    }

    @Test fun parsesBoundedTopLevelScalarsOnly() {
        val values = SessionIndexer.parseTopLevelScalars("{\"type\":\"message\",\"nested\":{\"type\":\"wrong\"},\"ok\":true}")
        assertEquals("message", values["type"])
        assertEquals("true", values["ok"])
        assertFalse(values.containsKey("nested"))
    }
}
