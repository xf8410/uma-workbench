package com.uma.workbench.protocol

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryTransferTest {
    private fun record(id: String, sid: String, body: String) = ProtocolHistoryRecord(
        id = id,
        timestamp = id.length.toLong(),
        channel = SendChannel.HLPATCH_PROXY,
        endpoint = "load/index",
        sid = sid,
        viewerId = 8410L,
        requestHeaders = linkedMapOf("SID" to sid, "Authorization" to "token-$id"),
        requestBody = body,
        requestBodyEncrypted = false,
        httpStatus = 200,
        protocolCode = 200,
        responseHeaders = linkedMapOf("Set-Cookie" to "cookie-$id"),
        responseBody = "response-$body",
        responseBodyDecrypted = "decrypted-$body",
        latencyMs = 42L,
        success = true,
        error = null
    )

    @Test fun consumerFailureRetainsCompleteLineAndContinuesWithLaterRecords() {
        val completeSid = "SID-" + "甲".repeat(8_000)
        val first = record("duplicate", completeSid, "first-" + "请".repeat(10_000))
        val second = record("valid", completeSid, "second-" + "求".repeat(11_000))
        val writer = StringWriter()
        ProtocolHistoryArchive.export(sequenceOf(first, second), writer)
        val completeFirstLine = writer.toString().lineSequence().first()
        val persisted = mutableListOf<ProtocolHistoryRecord>()

        val result = ProtocolHistoryArchive.import(StringReader(writer.toString())) { value ->
            if (value.id == "duplicate") error("duplicate primary key")
            persisted += value
        }

        assertEquals(1L, result.importedRecords)
        assertEquals(2L, result.totalLines)
        assertEquals(second, persisted.single())
        assertEquals(completeFirstLine, result.errors.single().completeLine)
        assertTrue(result.errors.single().message.contains("duplicate primary key"))
        assertEquals(completeSid, persisted.single().sid)
    }

    @Test fun transferStateReportsCountsWithoutReplacingCompleteErrorData() {
        val completeLine = "{broken:" + "错".repeat(20_000)
        val result = ProtocolArchiveImportResult(
            importedRecords = 7,
            totalLines = 8,
            errors = listOf(ProtocolArchiveLineError(3, completeLine, "parse failure"))
        )
        val state = ProtocolHistoryTransferState(operation = "导入", uri = "content://selected/document", importResult = result)

        assertTrue(state.summary.contains("7/8"))
        assertEquals(completeLine, state.importResult!!.errors.single().completeLine)
        assertEquals("content://selected/document", state.uri)
    }

    @Test fun archiveExportDoesNotCapRecordCountOrBodies() {
        val records = (0 until 250).asSequence().map { index ->
            record("id-$index", "sid-$index", "正文-$index-" + "长".repeat(2_000))
        }
        val writer = StringWriter()

        assertEquals(250L, ProtocolHistoryArchive.export(records, writer))
        val restored = mutableListOf<ProtocolHistoryRecord>()
        val result = ProtocolHistoryArchive.import(StringReader(writer.toString()), restored::add)
        assertEquals(250L, result.importedRecords)
        assertEquals(250, restored.size)
        assertTrue(restored.last().requestBody.endsWith("长".repeat(2_000)))
    }
}
