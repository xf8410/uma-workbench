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

    @Test fun fileAndSearchCallsUseCompleteInputs() = runBlocking {
        val outcomes = ReadonlyAgentToolExecutor(source).executeTurn(listOf(
            AiToolCall(0, "c1", "read_file_range", """{"uri":"content://a","startLine":2,"endLine":7}"""),
            AiToolCall(1, "c2", "search_workspace", """{"query":"Needle","offset":10,"caseSensitive":true}""")
        ))
        assertEquals("file:content://a", (outcomes[0] as AgentToolOutcome.Success).result.content)
        assertEquals("Needle:0:true", (outcomes[1] as AgentToolOutcome.Success).result.content)
    }

    @Test fun returnsEveryCharacterInOneCompleteResult() = runBlocking {
        val longSource = object : ReadonlyAgentToolDataSource by source { override suspend fun readCurrentFile() = "0123456789" }
        val store = InMemoryAgentToolResultStore()
        val executor = ReadonlyAgentToolExecutor(longSource, store)
        val result = (executor.execute(AiToolCall(0, "a", "read_current_file", "{}")) as AgentToolOutcome.Success).result
        assertEquals("0123456789", result.content)
        assertEquals(10, result.totalCharacterCount)
        assertEquals(null, result.nextOffset)
        assertTrue(result.complete)
        val reread = (executor.execute(AiToolCall(0, "b", "read_tool_result", """{"resultId":"${result.resultId}"}""")) as AgentToolOutcome.Success).result
        assertEquals("0123456789", reread.content)
        assertTrue(reread.complete)
    }

    @Test fun executesAllCallsInOneTurn() = runBlocking {
        val calls = (1..100).map { AiToolCall(it, "c$it", "read_current_file", "{}") }
        val outcomes = ReadonlyAgentToolExecutor(source).executeTurn(calls)
        assertEquals(100, outcomes.size)
        assertTrue(outcomes.all { it is AgentToolOutcome.Success })
    }

    @Test fun rejectsMutatingUnknownToolAsVisibleFailure() = runBlocking {
        val outcome = ReadonlyAgentToolExecutor(source).execute(AiToolCall(0, "bad", "write_file", "{}"))
        assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("不允许执行工具 write_file"))
    }
}
