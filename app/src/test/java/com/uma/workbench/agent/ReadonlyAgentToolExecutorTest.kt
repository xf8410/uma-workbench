package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadonlyAgentToolExecutorTest {
    private fun source(currentFile: String = "current") = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "a.kt\nb.kt"
        override suspend fun readCurrentFile() = currentFile
        override suspend fun readFile(uri: String) = "file:$uri"
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "$uri:$startLine-$endLine"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "$query:$offset:$caseSensitive"
        override suspend fun searchSymbol(query: String, offset: Int) = "symbol:$query:$offset"
        override suspend fun readIl2CppClass(className: String) = "class:$className"
        override suspend fun readProtocolRecord(id: String) = "protocol:$id"
        override suspend fun readSoSnapshot(endpoint: String?) = "so:${endpoint ?: "/summary"}"
        override suspend fun readDoc(id: String) = "doc:$id"
    }

    @Test fun readsCurrentFileWithPaging() = runBlocking {
        val executor = ReadonlyAgentToolExecutor(source("0123456789"), AgentToolExecutionLimits(pageCharacters = 4))
        val first = (executor.execute(AiToolCall(0, "a", "read_current_file", "{}")) as AgentToolOutcome.Success).result
        assertEquals("0123", first.content)
        assertEquals(10, first.totalCharacterCount)
        assertEquals(4, first.nextOffset)
        assertFalse(first.complete)

        val second = (executor.execute(AiToolCall(1, "b", "read_tool_result", """{"resultId":"${first.resultId}","offset":4}""")) as AgentToolOutcome.Success).result
        assertEquals("4567", second.content)
        assertEquals(4, second.startOffset)
        assertEquals(first.resultId, second.resultId)
        assertEquals(first.sha256, second.sha256)

        val third = (executor.execute(AiToolCall(2, "c", "read_tool_result", """{"resultId":"${first.resultId}","offset":8}""")) as AgentToolOutcome.Success).result
        assertEquals("89", third.content)
        assertTrue(third.complete)
        assertEquals(null, third.nextOffset)
        assertEquals("0123456789", first.content + second.content + third.content)
    }

    @Test fun storesSpecialToolPayloadCompletelyButMarksCompactManifestAsReference() = runBlocking {
        val persisted = "complete child report with SID=full-value"
        val manifest = "{\"type\":\"sub_agent_report_manifest\"}"
        val store = InMemoryAgentToolResultStore()
        val executor = ReadonlyAgentToolExecutor(source(), resultStore = store)

        val outcome = executor.executeSpecial(AiToolCall(0, "delegate", "delegate_subagents", "{}")) {
            AgentSpecialToolPayload(persistedContent = persisted, modelContent = manifest)
        } as AgentToolOutcome.Success

        assertEquals(manifest, outcome.result.content)
        assertEquals(persisted.length, outcome.result.totalCharacterCount)
        assertFalse(outcome.result.complete)
        assertEquals(0, outcome.result.nextOffset)
        assertNotEquals(persisted, outcome.result.content)
        assertEquals(persisted, store.read(outcome.result.resultId, 0, Int.MAX_VALUE).content)
    }

    @Test fun rejectsMutatingUnknownToolAsVisibleFailure() = runBlocking {
        val outcome = ReadonlyAgentToolExecutor(source()).execute(AiToolCall(0, "bad", "write_file", "{}"))
        assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("不允许执行工具 write_file"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTurnAboveCallBudgetBeforeExecution() {
        runBlocking {
            ReadonlyAgentToolExecutor(source(), AgentToolExecutionLimits(maxCallsPerTurn = 1))
                .executeTurn(listOf(
                    AiToolCall(0, "a", "read_current_file", "{}"),
                    AiToolCall(1, "b", "list_workspace_files", "{}")
                ))
        }
    }

    @Test fun executesAllCallsWithinBudget() = runBlocking {
        val calls = (1..8).map { AiToolCall(it, "c$it", "read_current_file", "{}") }
        val outcomes = ReadonlyAgentToolExecutor(source(), AgentToolExecutionLimits(maxCallsPerTurn = 8)).executeTurn(calls)
        assertEquals(8, outcomes.size)
        assertTrue(outcomes.all { it is AgentToolOutcome.Success })
    }
}
