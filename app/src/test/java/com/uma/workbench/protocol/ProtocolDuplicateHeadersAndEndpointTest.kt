package com.uma.workbench.protocol

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolDuplicateHeadersAndEndpointTest {
    private val duplicateResponseHeaders = listOf(
        ProtocolHeader("Set-Cookie", "first=完整值; Path=/"),
        ProtocolHeader("set-cookie", "second=第二值; Path=/api"),
        ProtocolHeader("X-Trace", "trace-one"),
        ProtocolHeader("X-Trace", "trace-two")
    )

    @Test fun headerCodecRetainsOrderCasingAndDuplicateNames() {
        val encoded = ProtocolHeaderCodec.encode(duplicateResponseHeaders)
        val decoded = ProtocolHeaderCodec.decode(encoded)
        assertEquals(duplicateResponseHeaders, decoded)
        assertEquals(listOf("first=完整值; Path=/", "second=第二值; Path=/api"), ProtocolHeaders.values(decoded, "SET-cookie"))
    }

    @Test fun legacyHeaderObjectStillImportsWithoutChangingValues() {
        val decoded = ProtocolHeaderCodec.decode("{\"Authorization\":\"Bearer complete-token\",\"Cookie\":\"a=b\"}")
        assertEquals(listOf(
            ProtocolHeader("Authorization", "Bearer complete-token"),
            ProtocolHeader("Cookie", "a=b")
        ), decoded)
    }

    @Test fun historyAndArchiveRoundTripRetainUnknownEndpointAndDuplicates() {
        val requestHeaders = listOf(
            ProtocolHeader("Cookie", "one=1"),
            ProtocolHeader("Cookie", "two=2"),
            ProtocolHeader("Authorization", "Bearer complete-token")
        )
        val original = ProtocolHistoryRecord(
            id = "unknown-endpoint",
            timestamp = 123L,
            channel = SendChannel.HLPATCH_PROXY,
            endpoint = "custom/nested/endpoint?mode=complete",
            sid = "complete-sid",
            viewerId = 8410L,
            requestHeaders = requestHeaders,
            requestBody = "complete request body",
            requestBodyEncrypted = false,
            httpStatus = 200,
            protocolCode = 200,
            responseHeaders = duplicateResponseHeaders,
            responseBody = "complete raw response",
            responseBodyDecrypted = "complete decrypted response",
            latencyMs = 9L,
            success = true,
            error = null
        )

        val log = original.toLogEntry()
        assertEquals(GameEndpoint.UNKNOWN, log.request.endpoint)
        assertEquals(original.endpoint, log.request.rawEndpoint)
        assertEquals(requestHeaders, log.request.headerEntries)
        assertEquals(duplicateResponseHeaders, log.response!!.headerEntries)
        assertEquals(original.endpoint, ProtocolHistoryRecord.from(log).endpoint)

        val writer = StringWriter()
        ProtocolHistoryArchive.export(sequenceOf(original), writer)
        val restored = mutableListOf<ProtocolHistoryRecord>()
        val result = ProtocolHistoryArchive.import(StringReader(writer.toString()), restored::add)
        assertTrue(result.errors.isEmpty())
        assertEquals(original, restored.single())
    }

    @Test fun completePresentationEmitsEveryDuplicateHeaderLine() {
        val record = ProtocolHistoryRecord(
            "record", 1L, SendChannel.HLPATCH_PROXY, "custom", null, null,
            listOf(ProtocolHeader("Cookie", "one=1"), ProtocolHeader("Cookie", "two=2")),
            "body", false, 200, 200, duplicateResponseHeaders, "raw", "decrypted", 1L, true, null
        )
        val text = ProtocolHistoryPresentation.completeRecord(record)
        assertTrue(text.contains("Cookie: one=1\nCookie: two=2"))
        assertTrue(text.contains("Set-Cookie: first=完整值; Path=/\nset-cookie: second=第二值; Path=/api"))
        assertTrue(text.contains("X-Trace: trace-one\nX-Trace: trace-two"))
    }
}
