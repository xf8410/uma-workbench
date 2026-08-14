package com.uma.workbench.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun preservesSensitiveResearchEvidenceExactly() {
        val raw = "Authorization=Bearer abc123; Cookie=session=xyz; token=t-1; password=p-1\nraw=00ff"

        val report = diagnosticReport(
            taskId = "task-1",
            stage = "protocol",
            event = "captured",
            code = "E_TEST",
            message = raw
        )

        assertEquals(raw, report.message)
    }
}
