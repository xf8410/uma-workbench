package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolIntelligenceTest {
    private val session = GameSession(
        sid = "complete-sid-value",
        viewerId = 8410L,
        accountToken = "complete-account-token",
        inheritCode = "ABCD-EFGH",
        appVer = "2.29.0",
        resVer = "resource-version",
        resVerHash = "resource-hash",
        deviceId = "device-id",
        deviceName = "device-name",
        platformOsVersion = "15",
        capturedAt = 1L,
        source = SessionSource.MANUAL_INPUT,
        bound = true
    )

    @Test fun loginTemplateKeepsCompleteCredentials() {
        val body = ProtocolRequestTemplates.forEndpoint(GameEndpoint.LOGIN, session)
        assertTrue(body.contains("ABCD-EFGH"))
        assertTrue(body.contains("complete-account-token"))
        assertTrue(body.contains("8410"))
    }

    @Test fun bootTemplateUsesAnonymousViewer() {
        val body = ProtocolRequestTemplates.forEndpoint(GameEndpoint.BOOT, session)
        assertTrue(body.contains("\"viewer_id\": 0"))
        assertTrue(body.contains("2.29.0"))
    }

    @Test fun mismatchDiagnosisExplainsSessionBinding() {
        val diagnosis = ProtocolDiagnostics.diagnose(218)
        assertEquals(218, diagnosis.code)
        assertTrue(diagnosis.retryable)
        assertTrue(diagnosis.explanation.contains("SID"))
        assertTrue(diagnosis.explanation.contains("viewer_id"))
    }

    @Test fun unknownDiagnosisDoesNotInventMeaning() {
        val diagnosis = ProtocolDiagnostics.diagnose(9999)
        assertFalse(diagnosis.retryable)
        assertEquals("未记录状态", diagnosis.title)
    }
}
