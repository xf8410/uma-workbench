package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryStoreTest {
    @Test fun historyRecordKeepsCompleteRequestResponseAndCredentials() {
        val longRequest = "请求".repeat(15_000)
        val longResponse = "响应".repeat(16_000)
        val completeSid = "complete-sid-" + "S".repeat(4_096)
        val request = GameRequest(GameEndpoint.LOAD_INDEX, completeSid, 8410L, longRequest, true, mapOf("SID" to completeSid, "Authorization" to "complete-account-token", "X-Repeated" to "complete-header-value"), 123L)
        val response = GameResponse(200, ProtocolStatusCode.OK, mapOf("Set-Cookie" to "complete-cookie-value"), longResponse, longResponse, 456L, 124L, true)
        val record = ProtocolHistoryRecord.from(ProtocolLogEntry(125L, request, response, null, SendChannel.HLPATCH_PROXY))
        assertEquals(completeSid, record.sid)
        assertEquals(completeSid, record.requestHeaders["SID"])
        assertEquals("complete-account-token", record.requestHeaders["Authorization"])
        assertEquals(longRequest, record.requestBody)
        assertEquals(longResponse, record.responseBody)
        assertEquals(longResponse, record.responseBodyDecrypted)
        assertEquals("complete-cookie-value", record.responseHeaders!!["Set-Cookie"])
        val restored = record.toLogEntry()
        assertEquals(completeSid, restored.request.sid)
        assertEquals(longRequest, restored.request.body)
        assertEquals(longResponse, restored.response!!.body)
    }

    @Test fun headerCodecRoundTripsNamesUnicodeAndCompleteValues() {
        val headers = linkedMapOf(
            "Authorization" to "token-" + "甲".repeat(20_000),
            "Set-Cookie" to "a=b; Path=/; HttpOnly",
            "X-Quotes" to "\\\"quoted\"\nnext-line"
        )
        val expected = ProtocolHeaders.fromMap(headers)
        val restored = ProtocolHeaderCodec.decode(ProtocolHeaderCodec.encode(headers))
        assertEquals(expected, restored)
    }

    @Test fun failedRequestKeepsErrorAndHasNoInventedResponse() {
        val entry = ProtocolLogEntry(1L, GameRequest(GameEndpoint.LOGIN, "complete-sid", 8410L, "complete-body"), null, "complete network error", SendChannel.OKHTTP_DIRECT)
        val record = ProtocolHistoryRecord.from(entry)
        assertNull(record.responseBody)
        assertEquals("complete network error", record.error)
        assertNull(record.toLogEntry().response)
    }

    @Test fun comparisonModelRetainsBothRecordsAndCompleteDiffValues() {
        val first = ProtocolHistoryRecord.from(ProtocolLogEntry(1L, GameRequest(GameEndpoint.LOGIN, "sid-1", 1L, "{\"value\":\"${"A".repeat(12_000)}\"}"), null, null, SendChannel.OKHTTP_DIRECT))
        val second = ProtocolHistoryRecord.from(ProtocolLogEntry(2L, GameRequest(GameEndpoint.LOGIN, "sid-2", 2L, "{\"value\":\"${"B".repeat(13_000)}\"}"), null, null, SendChannel.OKHTTP_DIRECT))
        val diff = ProtocolPayloadDiff.compare(first.requestBody, second.requestBody)
        val comparison = ProtocolHistoryComparison(first, second, diff, emptyList())
        assertSame(first, comparison.first)
        assertSame(second, comparison.second)
        assertTrue(comparison.requestBody.single().before!!.contains("A".repeat(12_000)))
        assertTrue(comparison.requestBody.single().after!!.contains("B".repeat(13_000)))
    }
}
