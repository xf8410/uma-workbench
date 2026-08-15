package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolEditorDefaultsTest {
    private val completeSid = "SID-" + "0123456789abcdef".repeat(64)
    private val session = GameSession(
        sid = completeSid,
        viewerId = 8410L,
        accountToken = "complete-token-value",
        inheritCode = "complete-inherit-code",
        appVer = "2.29.0",
        resVer = "complete-resource-version",
        resVerHash = "complete-resource-hash",
        deviceId = "complete-device-id",
        deviceName = "device",
        platformOsVersion = "15",
        capturedAt = 1L,
        source = SessionSource.HLPATCH,
        bound = true
    )

    @Test fun activeSessionAutofillsTheCompleteSidAndViewerId() {
        val defaults = ProtocolEditorDefaultsFactory.create(GameEndpoint.LOAD_INDEX.path, session)
        assertEquals(completeSid, defaults.sid)
        assertEquals("8410", defaults.viewerId)
        assertTrue(defaults.body.contains("\"viewer_id\": 8410"))
    }

    @Test fun endpointSwitchBuildsEditableCompleteLoginTemplate() {
        val defaults = ProtocolEditorDefaultsFactory.create(GameEndpoint.LOGIN.path, session)
        assertTrue(defaults.body.contains("complete-token-value"))
        assertTrue(defaults.body.contains("complete-inherit-code"))
        assertTrue(defaults.body.contains("8410"))
    }

    @Test fun absentSessionKeepsExistingManualCredentials() {
        val manualSid = "manual-complete-sid"
        val defaults = ProtocolEditorDefaultsFactory.create(
            endpointPath = GameEndpoint.START_SESSION.path,
            session = null,
            currentSid = manualSid,
            currentViewerId = "9001"
        )
        assertEquals(manualSid, defaults.sid)
        assertEquals("9001", defaults.viewerId)
        assertTrue(defaults.body.contains("\"viewer_id\": 0"))
    }

    @Test fun unknownEndpointFallsBackToLoginWithoutChangingCredentials() {
        val defaults = ProtocolEditorDefaultsFactory.create("unknown", session)
        assertEquals(completeSid, defaults.sid)
        assertTrue(defaults.body.contains("complete-token-value"))
    }
}
