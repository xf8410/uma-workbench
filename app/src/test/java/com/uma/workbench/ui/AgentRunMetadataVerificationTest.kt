package com.uma.workbench.ui

import com.uma.workbench.agent.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型输出验证结果在消息 metadata 中的往返持久化。
 * 验证对象附加到 rounds 数组末尾；旧格式解析与新字段共存均需保持兼容。
 */
class AgentRunMetadataVerificationTest {

    private fun verification(
        status: OutputVerification.VerificationStatus = OutputVerification.VerificationStatus.VERIFIED,
        total: Int = 3,
        success: Int = 2,
        failed: Int = 1,
        referenced: Int = 5,
        tools: Set<String> = setOf("read_current_file", "search_workspace"),
        warnings: List<String> = listOf("调用了 2 次工具但答案未引用工具结果内容")
    ) = OutputVerification(status, total, success, failed, referenced, tools, warnings)

    @Test fun verificationRoundTripsThroughMetadata() {
        val original = verification()
        val metadata = AgentRunMetadata.appendVerification("[]", original)
        val restored = AgentRunMetadata.verificationOf(metadata)

        assertNotNull(restored)
        assertEquals(original.status, restored!!.status)
        assertEquals(original.totalToolCalls, restored.totalToolCalls)
        assertEquals(original.successfulToolCalls, restored.successfulToolCalls)
        assertEquals(original.failedToolCalls, restored.failedToolCalls)
        assertEquals(original.evidenceSnippetsReferenced, restored.evidenceSnippetsReferenced)
        assertEquals(original.toolNamesUsed, restored.toolNamesUsed)
        assertEquals(original.warnings, restored.warnings)
    }

    @Test fun verificationAppendsToExistingRoundMetadata() {
        val rounds = """[{"round":1,"calls":[{"id":"c1","name":"read_current_file","status":"success","resultId":"r1","totalCharacterCount":100,"sha256":"ff"}]}]"""
        val metadata = AgentRunMetadata.appendVerification(rounds, verification())

        // verification 对象不引入假的 sub agent 引用，也不破坏原有解析
        assertTrue(AgentRunMetadata.subAgentReportRefs(metadata).isEmpty())
        assertNotNull(AgentRunMetadata.verificationOf(metadata))
    }

    @Test fun verificationWithSubAgentReportsCoexist() {
        val result = AgentToolResult("call", "delegate_subagents", "result-id", "manifest only", 0, 13, 12345, false, 0, "abc", 1)
        val round = ReadonlyAgentRound(1, "", listOf(AiToolCall(0, "call", "delegate_subagents", "{}")), listOf(AgentToolOutcome.Success(result)), "m", null)
        val metadata = AgentRunMetadata.appendVerification(AgentRunMetadata.toJson(listOf(round)), verification())

        assertEquals(listOf(PersistedSubAgentReportRef("result-id", 12345, "abc")), AgentRunMetadata.subAgentReportRefs(metadata))
        assertEquals(OutputVerification.VerificationStatus.VERIFIED, AgentRunMetadata.verificationOf(metadata)!!.status)
        assertTrue(!metadata.contains("manifest only"))
    }

    @Test fun metadataWithoutVerificationReturnsNull() {
        assertNull(AgentRunMetadata.verificationOf(null))
        assertNull(AgentRunMetadata.verificationOf(""))
        assertNull(AgentRunMetadata.verificationOf("""[{"round":1,"calls":[]}]"""))
    }

    @Test fun malformedMetadataDoesNotThrow() {
        assertNull(AgentRunMetadata.verificationOf("not-json"))
        assertNull(AgentRunMetadata.verificationOf("""{"verification":"object-not-array"}"""))
    }

    @Test fun unverifiedStatusRoundTrips() {
        val metadata = AgentRunMetadata.appendVerification(null, verification(status = OutputVerification.VerificationStatus.UNVERIFIED, tools = emptySet(), warnings = emptyList()))
        val restored = AgentRunMetadata.verificationOf(metadata)
        assertEquals(OutputVerification.VerificationStatus.UNVERIFIED, restored!!.status)
        assertTrue(restored.toolNamesUsed.isEmpty())
        assertTrue(restored.warnings.isEmpty())
    }
}
