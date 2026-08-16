package com.uma.workbench.ui

import com.uma.workbench.agent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMetadataTest {
    @Test fun roundTripKeepsReportReferenceWithoutReportBody() {
        val result = AgentToolResult("call", "delegate_subagents", "result-id", "manifest only", 0, 13, 12345, false, 0, "abc", 1)
        val round = ReadonlyAgentRound(1, "", listOf(AiToolCall(0, "call", "delegate_subagents", "{}")), listOf(AgentToolOutcome.Success(result)), "m", null)
        val metadata = AgentRunMetadata.toJson(listOf(round))

        val refs = AgentRunMetadata.subAgentReportRefs(metadata)
        assertEquals(listOf(PersistedSubAgentReportRef("result-id", 12345, "abc")), refs)
        assertTrue(!metadata.contains("manifest only"))
    }

    @Test fun legacyMetadataIsSafelyIgnored() {
        assertTrue(AgentRunMetadata.subAgentReportRefs("[{\"round\":1,\"calls\":2}]").isEmpty())
    }
}
