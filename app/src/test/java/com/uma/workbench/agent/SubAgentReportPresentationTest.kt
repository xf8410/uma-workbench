package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubAgentReportPresentationTest {
    @Test fun parsesSuccessFailureUsageAndEvidence() {
        val reports = SubAgentReportPresentation.parse("""
            {"type":"sub_agent_reports","reports":[
              {"taskId":"a","status":"success","requestId":"r","answer":"found","model":"m","usage":{"totalTokens":12},"evidence":[{"complete":true},{"complete":false}]},
              {"taskId":"b","status":"failure","error":"boom"}
            ]}
        """.trimIndent())!!

        assertEquals(2, reports.size)
        assertEquals("found", reports[0].answer)
        assertEquals(12L, reports[0].totalTokens)
        assertEquals(2, reports[0].evidenceCount)
        assertEquals(1, reports[0].completeEvidenceCount)
        assertEquals("boom", reports[1].error)
    }

    @Test fun ignoresOrdinaryAndMalformedToolContent() {
        assertNull(SubAgentReportPresentation.parse("ordinary result"))
        assertNull(SubAgentReportPresentation.parse("{\"type\":\"other\"}"))
        assertNull(SubAgentReportPresentation.parse("{\"type\":\"sub_agent_reports\",\"reports\":[{}]}"))
    }
}
