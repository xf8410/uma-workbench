package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadonlyAgentToolExecutorTest {
    private val source = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "a.kt\nb.kt"
        override suspend fun readCurrentFile() = "current"
        override suspend fun readFile(uri: String) = "file:$uri"
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "$uri:$startLine-$endLine"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "$query:$offset:$caseSensitive"
        override suspend fun searchSymbol(query: String, offset: Int) = "symbol:$query:$offset"
        override suspend fun readIl2CppClass(className: String) = "class:$className"
        override suspend fun readProtocolRecord(id: String) = "protocol:$id"
        override suspend fun readSoSnapshot(endpoint: String?) = "snapshot:${endpoint ?: "latest"}"
        override suspend fun readDoc(id: String) = "doc:$id"
    }

    @Test fun dispatchesValidatedRangeAndSearchArguments() = runBlocking {
        val executor = ReadonlyAgentToolExecutor(source)
        val outcomes = executor.executeTurn(listOf(
            AiToolCall(0, "c1", "read_file_range", """{"uri":"content://a","startLine":2,"endLine":7}"""),
            AiToolCall(1, "c2", "search_workspace", """{"query":"Needle","offset":10,"caseSensitive":true}""")
        ))
        assertEquals("content://a:2-7", (outcomes[0] as AgentToolOutcome.Success).result.completeContent)
        assertEquals("Needle:10:true", (outcomes[1] as AgentToolOutcome.Success).result.completeContent)
    }

    @Test fun rejectsMutatingUnknownToolAsVisibleFailure() = runBlocking {
        val outcome = ReadonlyAgentToolExecutor(source).execute(AiToolCall(0, "bad", "write_file", "{}"))
        assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("不允许执行工具 write_file"))
    }

    @Test fun boundedResultReportsOriginalSizeAndContinuation() = runBlocking {
        val longSource = object : ReadonlyAgentToolDataSource by source {
            override suspend fun readCurrentFile() = "0123456789"
        }
        val result = (ReadonlyAgentToolExecutor(longSource, AgentToolExecutionLimits(maxResultCharacters = 4)).execute(
            AiToolCall(0, "c", "read_current_file", "{}")
        ) as AgentToolOutcome.Success).result
        assertEquals("0123", result.completeContent)
        assertEquals(10, result.characterCount)
        assertTrue(result.truncated)
        assertEquals("characters:4-10", result.continuation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTurnAboveCallBudgetBeforeExecution() = runBlocking {
        ReadonlyAgentToolExecutor(source, AgentToolExecutionLimits(maxCallsPerTurn = 1)).executeTurn(listOf(
            AiToolCall(0, "a", "read_current_file", "{}"),
            AiToolCall(1, "b", "list_workspace_files", "{}")
        ))
    }

    @Test fun invalidRangeIsVisibleFailure() = runBlocking {
        val outcome = ReadonlyAgentToolExecutor(source).execute(
            AiToolCall(0, "range", "read_file_range", """{"uri":"u","startLine":8,"endLine":2}""")
        )
        assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("endLine 不能小于 startLine"))
    }
}
