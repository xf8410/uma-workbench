package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlpatchProxyEnvelopeTest {
    @Test fun requestRoundTripKeepsCompleteSidHeadersAndBody() {
        val completeSid = "sid-" + "甲".repeat(10_000)
        val completeBody = "{\"quoted\":\"\\\"value\\\"\",\"line\":\"one\\ntwo\",\"data\":\"${"文".repeat(30_000)}\"}"
        val request = GameRequest(
            endpoint = GameEndpoint.LOAD_INDEX,
            sid = completeSid,
            viewerId = 8410L,
            body = completeBody,
            bodyEncrypted = true,
            headers = linkedMapOf(
                "SID" to completeSid,
                "Authorization" to "complete-account-token",
                "Cookie" to "complete-cookie",
                "X-Quotes" to "\\\"quoted\"\nnext"
            ),
            timestamp = 123456L
        )

        val encoded = HlpatchProxyEnvelopeCodec.encodeRequest(request)
        val decoded = HlpatchProxyEnvelopeCodec.decodeRequest(encoded)

        assertEquals(request.endpoint.path, decoded.endpoint)
        assertEquals(completeSid, decoded.sid)
        assertEquals(completeSid, decoded.headers["SID"])
        assertEquals("complete-account-token", decoded.headers["Authorization"])
        assertEquals("complete-cookie", decoded.headers["Cookie"])
        assertEquals(completeBody, decoded.body)
        assertTrue(decoded.bodyEncrypted)
        assertEquals(123456L, decoded.timestamp)
    }

    @Test fun nullSidIsRepresentedWithoutInventingValue() {
        val request = GameRequest(GameEndpoint.BOOT, null, 0L, "complete boot body", timestamp = 1L)
        val decoded = HlpatchProxyEnvelopeCodec.decodeRequest(HlpatchProxyEnvelopeCodec.encodeRequest(request))
        assertNull(decoded.sid)
        assertEquals(0L, decoded.viewerId)
    }

    @Test fun responseRoundTripKeepsCompleteHeadersBodiesAndError() {
        val completeBody = "raw-response-" + "原".repeat(31_000)
        val completeDecrypted = "decrypted-response-" + "解".repeat(32_000)
        val response = HlpatchProxyResponseEnvelope(
            httpStatus = 200,
            protocolCode = 218,
            headers = linkedMapOf(
                "Set-Cookie" to "complete-response-cookie",
                "X-Unicode" to "完整响应头"
            ),
            body = completeBody,
            bodyDecrypted = completeDecrypted,
            latencyMs = 789L,
            success = false,
            error = "complete proxy error"
        )

        val decoded = HlpatchProxyEnvelopeCodec.decodeResponse(HlpatchProxyEnvelopeCodec.encodeResponse(response))
        assertEquals(response, decoded)
        val gameResponse = decoded.toGameResponse(999L)
        assertEquals(completeBody, gameResponse.body)
        assertEquals(completeDecrypted, gameResponse.bodyDecrypted)
        assertEquals("complete-response-cookie", gameResponse.headers["Set-Cookie"])
        assertEquals(ProtocolStatusCode.SID_SESSION_MISMATCH, gameResponse.protocolCode)
    }
}
