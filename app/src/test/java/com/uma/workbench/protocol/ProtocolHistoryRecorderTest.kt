package com.uma.workbench.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHistoryRecorderTest {
    @Test fun persistsCompleteEntryBeforePublishingIt() = runTest {
        val events = mutableListOf<String>()
        var persisted: ProtocolLogEntry? = null
        val recorder = ProtocolHistoryRecorder { entry ->
            events += "persist"
            persisted = entry
        }
        val completeSid = "sid-" + "S".repeat(20_000)
        val completeRequest = "request-" + "甲".repeat(30_000)
        val completeResponse = "response-" + "乙".repeat(31_000)
        val entry = ProtocolLogEntry(
            timestamp = 100L,
            request = GameRequest(
                endpoint = GameEndpoint.LOAD_INDEX,
                sid = completeSid,
                viewerId = 8410L,
                body = completeRequest,
                headers = mapOf("SID" to completeSid, "Authorization" to "token-完整值")
            ),
            response = GameResponse(
                statusCode = 200,
                protocolCode = ProtocolStatusCode.OK,
                headers = mapOf("Set-Cookie" to "cookie-完整值"),
                body = completeResponse,
                bodyDecrypted = completeResponse,
                latencyMs = 9L,
                timestamp = 101L,
                success = true
            ),
            error = null,
            channel = SendChannel.HLPATCH_PROXY
        )

        recorder.record(entry)
        events += "observed"

        assertEquals(listOf("persist", "observed"), events)
        assertEquals(entry, persisted)
        assertEquals(entry, recorder.entries.value.single())
        assertEquals(completeSid, persisted!!.request.sid)
        assertEquals(completeSid, persisted!!.request.headers["SID"])
        assertEquals(completeRequest, persisted!!.request.body)
        assertEquals(completeResponse, persisted!!.response!!.body)
        assertEquals(completeResponse, persisted!!.response!!.bodyDecrypted)
    }

    @Test fun persistenceFailureDoesNotPublishAnUnstoredEntry() = runTest {
        val recorder = ProtocolHistoryRecorder { throw IllegalStateException("完整持久化错误") }
        val entry = ProtocolLogEntry(
            timestamp = 1L,
            request = GameRequest(GameEndpoint.LOGIN, "complete-sid", 1L, "complete-body"),
            response = null,
            error = "complete-network-error",
            channel = SendChannel.OKHTTP_DIRECT
        )

        val failure = runCatching { recorder.record(entry) }.exceptionOrNull()

        assertEquals("完整持久化错误", failure?.message)
        assertTrue(recorder.entries.value.isEmpty())
    }

    @Test fun recordsEveryAttemptWithoutImplicitRowLimit() = runTest {
        val persisted = mutableListOf<ProtocolLogEntry>()
        val recorder = ProtocolHistoryRecorder { persisted += it }
        val count = 2_048

        repeat(count) { index ->
            recorder.record(
                ProtocolLogEntry(
                    timestamp = index.toLong(),
                    request = GameRequest(GameEndpoint.BOOT, "sid-$index", index.toLong(), "body-$index"),
                    response = null,
                    error = "error-$index",
                    channel = SendChannel.OKHTTP_DIRECT
                )
            )
        }

        assertEquals(count, persisted.size)
        assertEquals(count, recorder.entries.value.size)
        assertEquals("sid-${count - 1}", recorder.entries.value.last().request.sid)
        assertEquals("error-${count - 1}", recorder.entries.value.last().error)
    }
}
