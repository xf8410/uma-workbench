package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryInspectorTest {
    @Test fun exposesCompleteDetailsAndDiagnosisWithoutChangingValues() {
        val sid = "SID-" + "甲".repeat(20_000)
        val body = "request-" + "乙".repeat(30_000)
        val response = "response-" + "丙".repeat(31_000)
        val record = record("one", sid, body, response, 218)
        val inspector = ProtocolHistoryInspector()

        val detail = inspector.detail(record)

        assertEquals(record, detail.record)
        assertEquals(sid, detail.record.sid)
        assertEquals(sid, detail.record.requestHeaders["SID"])
        assertEquals(body, detail.record.requestBody)
        assertEquals(response, detail.record.responseBody)
        assertEquals(response, detail.record.responseBodyDecrypted)
        assertEquals("SID 与 viewer_id 不匹配", detail.diagnosis?.title)
    }

    @Test fun selectionBuildsCompleteRequestResponseAndHeaderDiff() {
        val beforeSid = "before-" + "甲".repeat(12_000)
        val afterSid = "after-" + "乙".repeat(13_000)
        val beforeBody = "{\"nested\":{\"value\":\"${"丙".repeat(14_000)}\"}}"
        val afterBody = "{\"nested\":{\"value\":\"${"丁".repeat(15_000)}\"}}"
        val first = record("first", beforeSid, beforeBody, "raw-before", 200)
        val second = record("second", afterSid, afterBody, "raw-after", 1055)
        val inspector = ProtocolHistoryInspector()

        inspector.toggle(first.id)
        assertNull(inspector.diff(listOf(first, second)))
        inspector.toggle(second.id)
        val diff = inspector.diff(listOf(first, second))

        assertNotNull(diff)
        assertEquals(first, diff!!.first)
        assertEquals(second, diff.second)
        assertEquals(beforeBody, diff.first.requestBody)
        assertEquals(afterBody, diff.second.requestBody)
        assertTrue(diff.requestHeaders.single().before!!.contains(beforeSid))
        assertTrue(diff.requestHeaders.single().after!!.contains(afterSid))
        assertEquals("$.nested.value", diff.requestBody.single().path)
        assertTrue(diff.requestBody.single().before!!.contains("丙".repeat(14_000)))
        assertTrue(diff.requestBody.single().after!!.contains("丁".repeat(15_000)))
        assertEquals("raw-before", diff.rawResponseBody.single().before)
        assertEquals("raw-after", diff.rawResponseBody.single().after)
    }

    @Test fun thirdSelectionKeepsExactlyTwoSidesWithoutDeletingAnyRecordData() {
        val records = listOf(
            record("one", "sid-one", "body-one", "response-one", 200),
            record("two", "sid-two", "body-two", "response-two", 200),
            record("three", "sid-three", "body-three", "response-three", 200)
        )
        val inspector = ProtocolHistoryInspector()
        records.forEach { inspector.toggle(it.id) }

        assertEquals(listOf("two", "three"), inspector.selectedIds.value)
        val diff = inspector.diff(records)!!
        assertEquals(records[1], diff.first)
        assertEquals(records[2], diff.second)
        assertEquals(3, records.size)
    }

    private fun record(id: String, sid: String, request: String, response: String, code: Int) = ProtocolHistoryRecord(
        id = id,
        timestamp = id.length.toLong(),
        channel = SendChannel.HLPATCH_PROXY,
        endpoint = GameEndpoint.LOAD_INDEX.path,
        sid = sid,
        viewerId = 8410,
        requestHeaders = linkedMapOf("SID" to sid, "Authorization" to "token-$id"),
        requestBody = request,
        requestBodyEncrypted = false,
        httpStatus = 200,
        protocolCode = code,
        responseHeaders = linkedMapOf("Set-Cookie" to "cookie-$id"),
        responseBody = response,
        responseBodyDecrypted = response,
        latencyMs = 10,
        success = code == 200,
        error = if (code == 200) null else "error-$id"
    )
}
