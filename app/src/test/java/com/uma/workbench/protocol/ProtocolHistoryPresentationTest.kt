package com.uma.workbench.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryPresentationTest {
    private val completeSid = "SID-" + "甲".repeat(10_000)
    private val request = "request-" + "请".repeat(20_000)
    private val rawResponse = "raw-" + "原".repeat(21_000)
    private val decrypted = "decrypted-" + "解".repeat(22_000)

    private fun record(id: String, body: String = request) = ProtocolHistoryRecord(
        id, 123L, SendChannel.HLPATCH_PROXY, "load/index", completeSid, 8410L,
        linkedMapOf("SID" to completeSid, "Authorization" to "complete-token"), body, false,
        200, 218, linkedMapOf("Set-Cookie" to "complete-cookie"), rawResponse, decrypted,
        42L, false, "complete-error"
    )

    @Test fun detailContainsEveryCompleteSensitiveAndPayloadValue() {
        val text = ProtocolHistoryPresentation.detail(
            ProtocolHistoryDetail(record("one"), ProtocolDiagnostics.diagnose(218))
        )
        assertTrue(text.contains(completeSid))
        assertTrue(text.contains(request))
        assertTrue(text.contains(rawResponse))
        assertTrue(text.contains(decrypted))
        assertTrue(text.contains("complete-token"))
        assertTrue(text.contains("complete-cookie"))
        assertTrue(text.contains("complete-error"))
        assertTrue(text.contains("SID 与 viewer_id 不匹配"))
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
}
