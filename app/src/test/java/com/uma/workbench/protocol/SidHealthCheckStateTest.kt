package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SidHealthCheckStateTest {
    private val completeSid = "SID-" + "0123456789abcdef".repeat(128)
    private val session = GameSession(
        sid = completeSid,
        viewerId = 8410L,
        accountToken = "complete-token",
        inheritCode = "complete-inherit-code",
        appVer = "2.29.0",
        resVer = "complete-res-ver",
        resVerHash = "complete-res-hash",
        deviceId = "complete-device-id",
        deviceName = "device",
        platformOsVersion = "15",
        capturedAt = 1L,
        source = SessionSource.MANUAL_INPUT,
        bound = true
    )

    @Test fun stateRetainsExactCompleteSidAndResponseEvidence() {
        val rawBody = "{\"result_code\":218,\"raw\":\"complete-response\"}"
        val decrypted = "{\"result_code\":218,\"decrypted\":\"complete-response\"}"
        val response = GameResponse(200, ProtocolStatusCode.SID_SESSION_MISMATCH, mapOf("X-Complete" to "header-value"), rawBody, decrypted, 9L, 10L, false)
        val result = SidHealthResult(session, SidDiagnosis.UNBOUND, 11L, 200, 218, "完整诊断", "重新绑定", response)
        val state = SidHealthCheckState(checkedSid = completeSid, viewerId = 8410L, result = result)

        assertEquals(completeSid, state.checkedSid)
        assertEquals(completeSid, state.result!!.session.sid)
        assertSame(response, state.result!!.response)
        assertEquals(rawBody, state.result!!.response!!.body)
        assertEquals(decrypted, state.result!!.response!!.bodyDecrypted)
        assertEquals("header-value", state.result!!.response!!.headers["X-Complete"])
    }

    @Test fun statusIncludesHttpAndProtocolDiagnostics() {
        val result = SidHealthResult(session, SidDiagnosis.EXPIRED, 11L, 401, 1055, "票据已过期", "重新获取 token", null)
        val text = SidHealthPresentation.statusText(SidHealthCheckState(checkedSid = completeSid, result = result))
        assertTrue(text.contains("已过期"))
        assertTrue(text.contains("票据已过期"))
        assertTrue(text.contains("HTTP 401"))
        assertTrue(text.contains("协议码 1055"))
    }

    @Test fun unboundResultShowsCompleteLoginChain() {
        val result = SidHealthResult(session, SidDiagnosis.UNBOUND, 11L, 200, 218, "未绑定", "重新登录", null)
        assertEquals(
            "登录链建议：重新登录 后续端点：tool/login → tool/start_session → load/index",
            SidHealthPresentation.loginChainText(result)
        )
    }

    @Test fun activeResultDoesNotSuggestUnnecessaryLogin() {
        val result = SidHealthResult(session, SidDiagnosis.ACTIVE, 11L, 200, 200, "可用", "无需操作", null)
        assertEquals("登录链建议：当前 SID 可用，无需重新登录。", SidHealthPresentation.loginChainText(result))
    }

    @Test fun errorsRemainCompleteForDiagnosis() {
        val completeError = "root failure\n" + "full stack evidence\n".repeat(200)
        val state = SidHealthCheckState(checkedSid = completeSid, error = completeError)
        assertEquals("检测未完成：$completeError", SidHealthPresentation.statusText(state))
    }
}
