package com.uma.workbench.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryPresentationTest {
    private val completeSid = "SID-" + "甲".repeat(10_000)
    private val request = "request-" + "请".repeat(20_000)
    private val rawResponse = "raw-" + "原".repeat(21_000)
    private val decrypted = "decrypted-" + "解".repeat(22_000)

    private fun record(id: String, body: String = request) = ProtocolHistoryRecord(
        id = id,
        timestamp = 123L,
        channel = SendChannel.HLPATCH_PROXY,
        endpoint = "load/index",
        sid = completeSid,
        viewerId = 8410L,
        requestHeaders = linkedMapOf(
            "SID" to completeSid,
            "Authorization" to "complete-token",
            "Cookie" to "request-cookie"
        ),
        requestBody = body,
        requestBodyEncrypted = false,
        httpStatus = 200,
        protocolCode = 218,
        responseHeaders = linkedMapOf(
            "Set-Cookie" to "complete-cookie",
            "X-Response-Token" to "response-token"
        ),
        responseBody = rawResponse,
        responseBodyDecrypted = decrypted,
        latencyMs = 42L,
        success = false,
        error = "complete-error"
    )

    @Test fun detailContainsEveryCompleteSensitiveAndPayloadValue() {
        val text = ProtocolHistoryPresentation.detail(
            ProtocolHistoryDetail(record("one"), ProtocolDiagnostics.diagnose(218))
        )
        assertCompleteRecordFields(text)
    }

    @Test fun completeAgentProjectionContainsEveryStoredFieldWithoutSubstitution() {
        val source = record("agent-read")
        val text = ProtocolHistoryPresentation.completeRecord(source)

        assertCompleteRecordFields(text)
        assertTrue(text.contains("id: ${source.id}"))
        assertTrue(text.contains("timestamp: ${source.timestamp}"))
        assertTrue(text.contains("channel: ${source.channel}"))
        assertTrue(text.contains("endpoint: ${source.endpoint}"))
        assertTrue(text.contains("viewer_id: ${source.viewerId}"))
        assertTrue(text.contains("request_body_encrypted: ${source.requestBodyEncrypted}"))
        assertTrue(text.contains("http_status: ${source.httpStatus}"))
        assertTrue(text.contains("protocol_code: ${source.protocolCode}"))
        assertTrue(text.contains("latency_ms: ${source.latencyMs}"))
        assertTrue(text.contains("success: ${source.success}"))
    }

    @Test fun diffContainsCompleteBeforeAndAfterValues() {
        val before = record("before", "before-" + "前".repeat(12_000))
        val after = record("after", "after-" + "后".repeat(13_000))
        val inspector = ProtocolHistoryInspector()
        inspector.toggle(before.id)
        inspector.toggle(after.id)
        val text = ProtocolHistoryPresentation.diff(inspector.diff(listOf(before, after))!!)
        assertTrue(text.contains(before.requestBody))
        assertTrue(text.contains(after.requestBody))
        assertTrue(text.contains("first_id: before"))
        assertTrue(text.contains("second_id: after"))
    }

    private fun assertCompleteRecordFields(text: String) {
        assertTrue(text.contains(completeSid))
        assertTrue(text.contains(request))
        assertTrue(text.contains(rawResponse))
        assertTrue(text.contains(decrypted))
        assertTrue(text.contains("complete-token"))
        assertTrue(text.contains("request-cookie"))
        assertTrue(text.contains("complete-cookie"))
        assertTrue(text.contains("response-token"))
        assertTrue(text.contains("complete-error"))
        assertTrue(text.contains("SID 与 viewer_id 不匹配"))
    }
}
