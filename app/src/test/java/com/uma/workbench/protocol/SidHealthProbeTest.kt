package com.uma.workbench.protocol

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SidHealthProbeTest {
    private fun session(
        sid: String = "complete-sid-value",
        viewerId: Long = 8410L,
        bound: Boolean = true,
        expired: Boolean = false
    ) = GameSession(
        sid = sid,
        viewerId = viewerId,
        accountToken = "complete-account-token",
        inheritCode = "complete-inherit-code",
        appVer = "2.29.0",
        resVer = "complete-resource-version",
        resVerHash = "complete-resource-hash",
        deviceId = "complete-device-id",
        deviceName = "complete-device-name",
        platformOsVersion = "15",
        capturedAt = 1L,
        source = SessionSource.MANUAL_INPUT,
        bound = bound,
        expired = expired
    )

    @Test fun anonymousSessionIsDiagnosedWithoutNetworkRequest() = runTest {
        var requested = false
        val result = SidHealthProbe { 100L }.probe(session(viewerId = 0L, bound = false)) {
            requested = true
            error("must not request")
        }
        assertEquals(SidDiagnosis.ANONYMOUS, result.diagnosis)
        assertEquals("complete-sid-value", result.session.sid)
        assertTrue(!requested)
    }

    @Test fun response218MarksPairUnboundAndKeepsCompleteResponse() = runTest {
        val completeBody = "{\"result_code\":218,\"detail\":\"complete mismatch evidence\"}"
        val response = GameResponse(200, ProtocolStatusCode.OK, mapOf("X-Full" to "complete-header"), completeBody, completeBody, 12L, 2L, false)
        val result = SidHealthProbe { 100L }.probe(session()) { request ->
            assertEquals("complete-sid-value", request.sid)
            assertEquals(8410L, request.viewerId)
            response
        }
        assertEquals(SidDiagnosis.UNBOUND, result.diagnosis)
        assertEquals(218, result.protocolCode)
        assertSame(response, result.response)
        assertEquals(completeBody, result.response!!.body)
    }

    @Test fun response1055MarksSessionExpired() = runTest {
        val response = GameResponse(200, ProtocolStatusCode.OK, emptyMap(), "{\"result_code\":1055}", null, 1L, 2L, false)
        val result = SidHealthProbe { 100L }.probe(session()) { response }
        assertEquals(SidDiagnosis.EXPIRED, result.diagnosis)
        assertEquals(GameEndpoint.LOGIN, LoginChainPlanner.next(result))
    }

    @Test fun successfulLoadIndexMarksSessionActive() = runTest {
        val response = GameResponse(200, ProtocolStatusCode.OK, emptyMap(), "{\"result_code\":200}", null, 1L, 2L, true)
        val result = SidHealthProbe { 100L }.probe(session()) { response }
        assertEquals(SidDiagnosis.ACTIVE, result.diagnosis)
        assertNull(LoginChainPlanner.next(result))
    }

    @Test fun plannerReturnsRemainingAndroidLoginChain() {
        assertEquals(
            listOf(GameEndpoint.LOGIN, GameEndpoint.START_SESSION, GameEndpoint.LOAD_INDEX),
            LoginChainPlanner.remainingFrom(GameEndpoint.LOGIN)
        )
        assertEquals(
            listOf(GameEndpoint.START_SESSION, GameEndpoint.LOAD_INDEX),
            LoginChainPlanner.remainingFrom(GameEndpoint.START_SESSION)
        )
    }
}
