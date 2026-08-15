package com.uma.workbench.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryTimelineTest {
    @Test fun loadsEveryRecordAndCompleteOriginalValues() = runTest {
        val completeSid = "SID-" + "甲".repeat(20_000)
        val completeRequest = "request-" + "乙".repeat(30_000)
        val completeResponse = "response-" + "丙".repeat(31_000)
        val records = (0 until 2_048).map { index ->
            ProtocolHistoryRecord(
                id = "record-$index",
                timestamp = index.toLong(),
                channel = SendChannel.HLPATCH_PROXY,
                endpoint = GameEndpoint.LOAD_INDEX.path,
                sid = if (index == 2_047) completeSid else "sid-$index",
                viewerId = index.toLong(),
                requestHeaders = mapOf("SID" to if (index == 2_047) completeSid else "sid-$index", "Authorization" to "token-$index"),
                requestBody = if (index == 2_047) completeRequest else "request-$index",
                requestBodyEncrypted = false,
                httpStatus = 200,
                protocolCode = 200,
                responseHeaders = mapOf("Set-Cookie" to "cookie-$index"),
                responseBody = if (index == 2_047) completeResponse else "response-$index",
                responseBodyDecrypted = if (index == 2_047) completeResponse else "decrypted-$index",
                latencyMs = index.toLong(),
                success = true,
                error = null
            )
        }
        val timeline = ProtocolHistoryTimeline { records }

        timeline.reload()

        assertEquals(records.size, timeline.records.value.size)
        val last = timeline.records.value.last()
        assertEquals(completeSid, last.sid)
        assertEquals(completeSid, last.requestHeaders["SID"])
        assertEquals(completeRequest, last.requestBody)
        assertEquals(completeResponse, last.responseBody)
        assertEquals(completeResponse, last.responseBodyDecrypted)
        assertEquals(ProtocolHistoryLoadState.Loaded(records.size), timeline.loadState.value)
    }

    @Test fun failedReloadKeepsPreviouslyLoadedCompleteRecordsAndReportsError() = runTest {
        val original = ProtocolHistoryRecord(
            id = "complete", timestamp = 1, channel = SendChannel.OKHTTP_DIRECT,
            endpoint = GameEndpoint.LOGIN.path, sid = "complete-sid", viewerId = 8410,
            requestHeaders = mapOf("Authorization" to "complete-token"), requestBody = "complete-request",
            requestBodyEncrypted = false, httpStatus = null, protocolCode = null,
            responseHeaders = null, responseBody = null, responseBodyDecrypted = null,
            latencyMs = null, success = null, error = "complete-error"
        )
        var fail = false
        val timeline = ProtocolHistoryTimeline {
            if (fail) error("database unavailable") else listOf(original)
        }
        timeline.reload()
        fail = true

        timeline.reload()

        assertEquals(listOf(original), timeline.records.value)
        val state = timeline.loadState.value
        assertTrue(state is ProtocolHistoryLoadState.Failed)
        assertEquals("database unavailable", (state as ProtocolHistoryLoadState.Failed).message)
    }
}
