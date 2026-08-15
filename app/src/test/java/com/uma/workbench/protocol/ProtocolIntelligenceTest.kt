package com.uma.workbench.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun responseInterpreterReadsNestedProtocolCodeAndKeepsCompleteBody() {
        val completeBody = "{\"data\":{\"result_code\":218},\"message\":\"complete response text\"}"
        val interpreted = ProtocolResponseInterpreter.interpret(200, completeBody)
        assertEquals(200, interpreted.httpStatus)
        assertEquals(218, interpreted.protocolCode)
        assertEquals("SID 与 viewer_id 不匹配", interpreted.diagnosis.title)
        assertEquals(completeBody, interpreted.completeBody)
    }

    @Test fun responseInterpreterFallsBackToHttpStatusForNonJson() {
        val completeBody = "complete non-json response"
        val interpreted = ProtocolResponseInterpreter.interpret(1055, completeBody)
        assertEquals(1055, interpreted.protocolCode)
        assertEquals(completeBody, interpreted.completeBody)
        assertNull(interpreted.parsedJson)
    }

    @Test fun payloadDiffKeepsEveryCompleteChangedValue() {
        val longBefore = "甲".repeat(12_000)
        val longAfter = "乙".repeat(13_000)
        val before = "{\"nested\":{\"value\":\"$longBefore\"},\"removed\":true,\"items\":[1,2]}"
        val after = "{\"nested\":{\"value\":\"$longAfter\"},\"added\":false,\"items\":[1,3,4]}"
        val diff = ProtocolPayloadDiff.compare(before, after).associateBy { it.path }
        assertTrue(diff.getValue("$.nested.value").before!!.contains(longBefore))
        assertTrue(diff.getValue("$.nested.value").after!!.contains(longAfter))
        assertEquals(ProtocolDiffKind.REMOVED, diff.getValue("$.removed").kind)
        assertEquals(ProtocolDiffKind.ADDED, diff.getValue("$.added").kind)
        assertEquals(ProtocolDiffKind.CHANGED, diff.getValue("$.items[1]").kind)
        assertEquals(ProtocolDiffKind.ADDED, diff.getValue("$.items[2]").kind)
    }

    @Test fun nonJsonDiffKeepsBothCompletePayloads() {
        val before = "before\n" + "A".repeat(20_000)
        val after = "after\n" + "B".repeat(21_000)
        val entry = ProtocolPayloadDiff.compare(before, after).single()
        assertEquals(before, entry.before)
        assertEquals(after, entry.after)
    }
}
