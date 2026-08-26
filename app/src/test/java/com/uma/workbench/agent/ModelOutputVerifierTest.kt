package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOutputVerifierTest {

    private fun makeRound(
        index: Int = 1,
        assistantText: String = "",
        toolCalls: List<AiToolCall> = emptyList(),
        toolOutcomes: List<AgentToolOutcome> = emptyList()
    ): ReadonlyAgentRound = ReadonlyAgentRound(
        index = index,
        assistantText = assistantText,
        toolCalls = toolCalls,
        toolOutcomes = toolOutcomes,
        model = "test-model",
        usage = null
    )

    private fun makeSuccessOutcome(
        callId: String = "call-1",
        toolName: String = "read_file",
        content: String = "Hello World from the file"
    ): AgentToolOutcome.Success = AgentToolOutcome.Success(
        AgentToolResult(
            callId = callId,
            toolName = toolName,
            resultId = "result-$callId",
            content = content,
            startOffset = 0,
            endOffsetExclusive = content.length,
            totalCharacterCount = content.length,
            complete = true,
            nextOffset = null,
            sha256 = "abc123",
            elapsedMillis = 10
        )
    )

    private fun makeFailureOutcome(
        callId: String = "call-1",
        toolName: String = "read_file",
        error: String = "File not found"
    ): AgentToolOutcome.Failure = AgentToolOutcome.Failure(
        AgentToolFailure(
            callId = callId,
            toolName = toolName,
            completeError = error,
            elapsedMillis = 5
        )
    )

    private fun makeToolCall(
        id: String = "call-1",
        name: String = "read_file"
    ): AiToolCall = AiToolCall(
        index = 0,
        id = id,
        name = name,
        completeArgumentsJson = "{}"
    )

    private fun makeRunResult(
        answer: String,
        rounds: List<ReadonlyAgentRound>
    ): ReadonlyAgentRunResult = ReadonlyAgentRunResult(
        requestId = "req-1",
        completeAnswer = answer,
        rounds = rounds,
        usage = AiTokenUsage(),
        model = "test-model",
        messages = emptyList()
    )

    @Test
    fun trivialShortAnswer_noToolCalls_returnsTrivial() {
        val result = makeRunResult(
            answer = "你好",
            rounds = listOf(makeRound(index = 1, assistantText = "你好"))
        )
        val verification = ModelOutputVerifier.verify(result)
        assertEquals(OutputVerification.VerificationStatus.TRIVIAL, verification.status)
        assertEquals(0, verification.totalToolCalls)
        assertTrue(verification.warnings.isEmpty())
    }

    @Test
    fun longAnswerNoToolCalls_returnsUnverified() {
        val longAnswer = "这是一个很长的回答，" +
            "超过了八十个字符的阈值限制，" +
            "但是没有调用任何工具来验证内容，" +
            "因此应该被标记为未验证状态，" +
            "而不是简短回复或已验证状态。" +
            "模型不应该在不查阅任何文件或数据的情况下，" +
            "就生成如此长的回答，这表示输出可能缺乏证据支撑。"
        val result = makeRunResult(
            answer = longAnswer,
            rounds = listOf(makeRound(index = 1, assistantText = longAnswer))
        )
        val verification = ModelOutputVerifier.verify(result)
        assertEquals(OutputVerification.VerificationStatus.UNVERIFIED, verification.status)
        assertTrue(verification.warnings.isNotEmpty())
    }

    @Test
    fun answerWithToolEvidence_returnsVerified() {
        val toolContent = "The offset for CharacterRoot is 0x1A2B at class index 42"
        val call = makeToolCall(id = "call-1", name = "read_il2cpp_class")
        val outcome = makeSuccessOutcome(callId = "call-1", content = toolContent)
        val answer = "Based on analysis, $toolContent. This confirms the structure."
        val result = makeRunResult(
            answer = answer,
            rounds = listOf(makeRound(
                index = 1,
                toolCalls = listOf(call),
                toolOutcomes = listOf(outcome)
            ))
        )
        val verification = ModelOutputVerifier.verify(result)
        assertEquals(OutputVerification.VerificationStatus.VERIFIED, verification.status)
        assertTrue(verification.evidenceSnippetsReferenced > 0)
        assertTrue(verification.toolNamesUsed.contains("read_il2cpp_class"))
    }

    @Test
    fun toolsCalledButAnswerDoesNotReferenceResults_returnsPartial() {
        val toolContent = "Some obscure internal data that won't appear in the answer"
        val call = makeToolCall(id = "call-1", name = "read_file")
        val outcome = makeSuccessOutcome(callId = "call-1", content = toolContent)
        val answer = "I think the answer is something completely unrelated to the tool output."
        val result = makeRunResult(
            answer = answer,
            rounds = listOf(makeRound(
                index = 1,
                toolCalls = listOf(call),
                toolOutcomes = listOf(outcome)
            ))
        )
        val verification = ModelOutputVerifier.verify(result)
        assertEquals(OutputVerification.VerificationStatus.PARTIAL, verification.status)
        assertEquals(0, verification.evidenceSnippetsReferenced)
        assertTrue(verification.warnings.isNotEmpty())
    }

    @Test
    fun allToolsFailed_returnsUnverified() {
        val call = makeToolCall(id = "call-1", name = "read_file")
        val outcome = makeFailureOutcome(callId = "call-1", error = "Permission denied")
        val answer = "The file could not be read due to permission issues."
        val result = makeRunResult(
            answer = answer,
            rounds = listOf(makeRound(
                index = 1,
                toolCalls = listOf(call),
                toolOutcomes = listOf(outcome)
            ))
        )
        val verification = ModelOutputVerifier.verify(result)
        assertEquals(OutputVerification.VerificationStatus.UNVERIFIED, verification.status)
        assertEquals(1, verification.failedToolCalls)
        assertEquals(0, verification.successfulToolCalls)
    }

    @Test
    fun multipleToolCalls_countsCorrectly() {
        val calls = listOf(
            makeToolCall(id = "call-1", name = "list_workspace_files"),
            makeToolCall(id = "call-2", name = "read_file"),
            makeToolCall(id = "call-3", name = "search_symbol")
        )
        val outcomes = listOf(
            makeSuccessOutcome(callId = "call-1", content = "file1.txt\nfile2.txt"),
            makeSuccessOutcome(callId = "call-2", content = "Content of file1.txt here"),
            makeFailureOutcome(callId = "call-3", error = "Symbol not found")
        )
        val answer = "The workspace contains file1.txt\nfile2.txt and Content of file1.txt here."
        val result = makeRunResult(
            answer = answer,
            rounds = listOf(makeRound(
                index = 1,
                toolCalls = calls,
                toolOutcomes = outcomes
            ))
        )
        val verification = ModelOutputVerifier.verify(result)
        assertEquals(3, verification.totalToolCalls)
        assertEquals(2, verification.successfulToolCalls)
        assertEquals(1, verification.failedToolCalls)
        assertEquals(3, verification.toolNamesUsed.size)
        assertTrue(verification.evidenceSnippetsReferenced > 0)
    }

    @Test
    fun summary_describesVerificationResult() {
        val result = makeRunResult(
            answer = "你好",
            rounds = listOf(makeRound(index = 1, assistantText = "你好"))
        )
        val verification = ModelOutputVerifier.verify(result)
        val summary = verification.summary()
        assertTrue(summary.contains("简短回复"))
    }
}
