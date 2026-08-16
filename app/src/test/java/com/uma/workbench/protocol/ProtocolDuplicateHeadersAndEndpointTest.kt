package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolDuplicateHeadersAndEndpointTest {

    @Test fun duplicateHeadersPreserveOrderAndNames() {
        val entries = listOf(
            ProtocolHeader("Set-Cookie", "session=abc"),
            ProtocolHeader("Set-Cookie", "tracking=xyz"),
            ProtocolHeader("X-Custom", "value1")
        )
        val encoded = ProtocolHeaderCodec.encodeEntries(entries)
        val decoded = ProtocolHeaderCodec.decodeEntries(encoded)

        assertEquals(3, decoded.size)
        assertEquals("Set-Cookie", decoded[0].name)
        assertEquals("session=abc", decoded[0].value)
        assertEquals("Set-Cookie", decoded[1].name)
        assertEquals("tracking=xyz", decoded[1].value)
        assertEquals("X-Custom", decoded[2].name)
        assertEquals("value1", decoded[2].value)
    }

    @Test fun headerCodecBackwardCompatWithOldMapFormat() {
        // 旧格式：JSON object {"name":"value",...}
        val oldFormat = """{"SID":"test-sid","ViewerID":"12345"}"""
        val decoded = ProtocolHeaderCodec.decode(oldFormat)

        assertEquals("test-sid", decoded["SID"])
        assertEquals("12345", decoded["ViewerID"])
    }

    @Test fun unknownEndpointRoundTripsThroughHistoryStore() {
        val unknownPath = "some/new/endpoint/v2"
        val endpoint = GameEndpoint.fromPath(unknownPath)

        assertEquals(GameEndpoint.UNKNOWN, endpoint)

        val request = GameRequest(
            endpoint = endpoint,
            sid = "test-sid",
            viewerId = 123L,
            body = "{}",
            headers = mapOf("SID" to "test-sid"),
            rawEndpoint = unknownPath
        )
        val entry = ProtocolLogEntry(
            timestamp = 1000L,
            request = request,
            response = null,
            error = null,
            channel = SendChannel.HLPATCH_PROXY
        )
        val record = ProtocolHistoryRecord.from(entry)

        assertEquals(unknownPath, record.endpoint)

        val logEntry = record.toLogEntry()
        assertEquals(unknownPath, logEntry.request.rawEndpoint)
        assertEquals(GameEndpoint.UNKNOWN, logEntry.request.endpoint)
    }

    @Test fun hlpatchEnvelopeCarriesDuplicateHeaders() {
        val request = GameRequest(
            endpoint = GameEndpoint.LOGIN,
            sid = "sid",
            viewerId = 1L,
            body = "{}",
            headers = mapOf("SID" to "sid", "Authorization" to "token"),
            rawEndpoint = "tool/login"
        )
        val envelope = HlpatchProxyEnvelopeCodec.from(request)

        assertEquals(2, envelope.headerEntries.size)
        assertEquals("SID", envelope.headerEntries[0].name)
        assertEquals("sid", envelope.headerEntries[0].value)
        assertEquals("Authorization", envelope.headerEntries[1].name)
        assertEquals("token", envelope.headerEntries[1].value)
    }

    @Test fun hlpatchEnvelopeEncodesAndDecodesHeaderEntries() {
        val request = GameRequest(
            endpoint = GameEndpoint.LOAD_INDEX,
            sid = "sid",
            viewerId = 1L,
            body = "{}",
            headers = mapOf("SID" to "sid", "Cookie" to "a=1"),
            rawEndpoint = "load/index"
        )
        val encoded = HlpatchProxyEnvelopeCodec.encodeRequest(request)
        val decoded = HlpatchProxyEnvelopeCodec.decodeRequest(encoded)

        assertEquals(2, decoded.headerEntries.size)
        assertEquals("SID", decoded.headerEntries[0].name)
        assertEquals("sid", decoded.headerEntries[0].value)
        assertEquals("Cookie", decoded.headerEntries[1].name)
        assertEquals("a=1", decoded.headerEntries[1].value)
    }
}
