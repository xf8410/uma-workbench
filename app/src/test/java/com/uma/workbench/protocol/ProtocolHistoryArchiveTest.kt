package com.uma.workbench.protocol

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryArchiveTest {
    private fun record(
        id: String,
        sid: String?,
        requestBody: String,
        responseBody: String?
    ) = ProtocolHistoryRecord(
        id = id,
        timestamp = 123456789L,
        channel = SendChannel.HLPATCH_PROXY,
        endpoint = "load/index",
        sid = sid,
        viewerId = 8410L,
        requestHeaders = linkedMapOf(
            "SID" to sid.orEmpty(),
            "Authorization" to "complete-account-token",
            "X-Unicode" to "完整请求头"
        ),
        requestBody = requestBody,
        requestBodyEncrypted = false,
        httpStatus = if (responseBody == null) null else 200,
        protocolCode = if (responseBody == null) null else 200,
        responseHeaders = if (responseBody == null) null else linkedMapOf("Set-Cookie" to "complete-cookie"),
        responseBody = responseBody,
        responseBodyDecrypted = responseBody,
        latencyMs = if (responseBody == null) null else 42L,
        success = if (responseBody == null) null else true,
        error = if (responseBody == null) "complete failure information" else null
    )

    @Test fun exportImportRoundTripKeepsEveryCompleteValue() {
        val completeSid = "SID-" + "甲".repeat(10_000)
        val completeRequest = "request\n" + "请".repeat(30_000)
        val completeResponse = "response\n" + "应".repeat(31_000)
        val original = record("one", completeSid, completeRequest, completeResponse)
        val writer = StringWriter()

        assertEquals(1L, ProtocolHistoryArchive.export(sequenceOf(original), writer))

        val restored = mutableListOf<ProtocolHistoryRecord>()
        val result = ProtocolHistoryArchive.import(StringReader(writer.toString()), restored::add)
        assertEquals(1L, result.importedRecords)
        assertEquals(1L, result.totalLines)
        assertTrue(result.errors.isEmpty())
        assertEquals(original, restored.single())
        assertEquals(completeSid, restored.single().sid)
        assertEquals(completeRequest, restored.single().requestBody)
        assertEquals(completeResponse, restored.single().responseBody)
    }

    @Test fun exportPreservesRecordOrderAndNullResponse() {
        val first = record("first", "complete-sid-one", "first body", "first response")
        val second = record("second", "complete-sid-two", "second body", null)
        val writer = StringWriter()
        ProtocolHistoryArchive.export(sequenceOf(first, second), writer)

        val restored = mutableListOf<ProtocolHistoryRecord>()
        val result = ProtocolHistoryArchive.import(StringReader(writer.toString()), restored::add)
        assertEquals(listOf("first", "second"), restored.map { it.id })
        assertEquals(2L, result.importedRecords)
        assertNull(restored[1].responseBody)
        assertEquals("complete failure information", restored[1].error)
    }

    @Test fun malformedLineIsReportedInFullAndLaterRecordsContinue() {
        val valid = record("valid", "complete-sid", "complete request", "complete response")
        val malformed = "{not-json:" + "错误".repeat(12_000)
        val input = malformed + "\n" + ProtocolHistoryArchive.encode(valid) + "\n"
        val restored = mutableListOf<ProtocolHistoryRecord>()

        val result = ProtocolHistoryArchive.import(StringReader(input), restored::add)

        assertEquals(1L, result.importedRecords)
        assertEquals(2L, result.totalLines)
        assertEquals(valid, restored.single())
        assertEquals(malformed, result.errors.single().completeLine)
        assertEquals(1L, result.errors.single().lineNumber)
    }

    @Test fun emptyArchiveExportsZeroRecords() {
        val writer = StringWriter()
        assertEquals(0L, ProtocolHistoryArchive.export(emptySequence(), writer))
        assertEquals("", writer.toString())
    }
}
