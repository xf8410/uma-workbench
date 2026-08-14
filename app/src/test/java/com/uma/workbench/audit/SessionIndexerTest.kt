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
        assertEquals(1723636801000, first.records[1].timestampMillis)
        val second = SessionIndexer.readBatch("s", { ByteArrayInputStream(source) }, first.checkpoint, maxRecords = 2)
        assertTrue(second.complete)
        assertNull(second.checkpoint)
        assertEquals(2L, second.records.single().recordIndex)
        assertTrue(second.records.single().malformed)
    }

    @Test fun indexesAllNestedObjectsArraysUnicodeAndLongValues() {
        val longValue = "甲".repeat(12_000)
        val line = "{\"metadata\":{\"type\":\"message\",\"timestamp\":\"2026-08-14T12:00:00Z\"},\"items\":[{\"id\":1},true],\"text\":\"$longValue\"}"
        val batch = SessionIndexer.readBatch("source-A", { ByteArrayInputStream(line.toByteArray()) })
        val fields = batch.fields.associateBy { it.fieldPath }
        assertEquals("message", batch.records.single().recordType)
        assertEquals("message", fields["metadata.type"]?.normalizedValue)
        assertEquals("1", fields["items[0].id"]?.normalizedValue)
        assertEquals("true", fields["items[1]"]?.normalizedValue)
        assertEquals(longValue, fields["text"]?.normalizedValue)
        assertTrue(batch.records.single().timestampMillis != null)
    }

    @Test fun indexesEveryArrayItemAndDeepField() {
        val array = (0 until 96).joinToString(",") { it.toString() }
        val line = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":{\"h\":{\"i\":\"deep\"}}}}}}}},\"items\":[$array]}"
        val fields = SessionIndexer.parseScalars(line).associateBy { it.path }
        assertEquals("deep", fields["a.b.c.d.e.f.g.h.i"]?.value)
        assertEquals("95", fields["items[95]"]?.value)
        assertEquals(97, fields.size)
    }

    @Test fun parseTopLevelProjectionExcludesNestedFields() {
        val values = SessionIndexer.parseTopLevelScalars("{\"type\":\"message\",\"nested\":{\"type\":\"wrong\"},\"ok\":true}")
        assertEquals("message", values["type"])
        assertEquals("true", values["ok"])
        assertFalse(values.containsKey("nested.type"))
    }
}
