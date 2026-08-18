package com.uma.workbench.diagnostics

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchErrorMapperTest {
    @Test fun connectionAbortBecomesClearChineseMessageAndPreservesPartialImpact() {
        val result = WorkbenchErrorMapper.map(SocketException("Software caused connection abort"), 12_438).userFacing
        assertEquals(WorkbenchErrorCode.NETWORK_CONNECTION_LOST, result.code)
        assertEquals("WB-NET-004", result.code.stableCode)
        assertTrue(result.title.contains("连接"))
        assertTrue(result.impact.contains("12,438"))
        assertTrue(result.retryable)
        assertFalse(result.displayText.contains("Software caused connection abort"))
        assertFalse(result.displayText.contains("SocketException"))
    }

    @Test fun dnsAndTimeoutAreNotCollapsedIntoOneGenericNetworkError() {
        val dns = WorkbenchErrorMapper.map(UnknownHostException("raw host")).userFacing
        val timeout = WorkbenchErrorMapper.map(SocketTimeoutException("raw timeout")).userFacing
        assertEquals(WorkbenchErrorCode.NETWORK_DNS_FAILURE, dns.code)
        assertEquals(WorkbenchErrorCode.NETWORK_TIMEOUT, timeout.code)
        assertTrue(dns.title.contains("服务器地址"))
        assertTrue(timeout.title.contains("超时"))
    }

    @Test fun providerStatusesHaveStableDistinctMeanings() {
        assertEquals(WorkbenchErrorCode.AI_AUTHENTICATION_FAILED, WorkbenchErrorMapper.map(AiHttpException(401, "secret raw body")).userFacing.code)
        assertEquals(WorkbenchErrorCode.AI_RATE_LIMITED, WorkbenchErrorMapper.map(AiHttpException(429, "raw")).userFacing.code)
        assertEquals(WorkbenchErrorCode.AI_SERVER_UNAVAILABLE, WorkbenchErrorMapper.map(AiHttpException(503, "raw")).userFacing.code)
    }

    @Test fun rawProviderBodyNeverAppearsInNormalUiText() {
        val raw = "provider-private-error-body"
        val visible = WorkbenchErrorMapper.map(AiHttpException(401, raw)).userFacing.displayText
        assertFalse(visible.contains(raw))
        assertTrue(visible.contains("API 凭据"))
        assertTrue(visible.contains("诊断编号"))
    }
}
