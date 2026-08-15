package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolCallNormalizationTest {
    @Test fun assemblesFragmentsDropsEmptyPlaceholderAndCanonicalizesArguments() {
        val accumulator = AiToolCallAccumulator()
        accumulator.append(AiToolCallDelta(0, idFragment = "call-1", nameFragment = " search_workspace "))
        accumulator.append(AiToolCallDelta(0, argumentsFragment = "{\"query\":\"x\",\"caseSensitive\":false,\"offset\":0}"))
        accumulator.append(AiToolCallDelta(1))

        val calls = accumulator.validatedSnapshot()

        assertEquals(1, calls.size)
        assertEquals("search_workspace", calls.single().name)
        assertEquals("{\"caseSensitive\":false,\"offset\":0,\"query\":\"x\"}", calls.single().completeArgumentsJson)
    }

    @Test fun normalizesEmptyArgumentsOnlyForToolsWhoseSchemaAllowsIt() {
        val call = AiToolCallNormalizer.normalize(listOf(AiToolCall(0, "a", "read_current_file", ""))).single()
        assertEquals("{}", call.completeArgumentsJson)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingArgumentsForParameterizedTool() {
        AiToolCallNormalizer.normalize(listOf(AiToolCall(0, "a", "read_file", "")))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsSameIdWithConflictingMeaning() {
        AiToolCallNormalizer.normalize(listOf(
            AiToolCall(0, "same", "read_file", "{\"uri\":\"a\"}"),
            AiToolCall(1, "same", "read_file", "{\"uri\":\"b\"}")
        ))
    }

    @Test fun executesEquivalentCallsOnceButReturnsOneOutcomePerDistinctCallId() = runBlocking {
        var reads = 0
        val source = object : ReadonlyAgentToolDataSource {
            override suspend fun listWorkspaceFiles() = "files"
            override suspend fun readCurrentFile(): String { reads++; return "content" }
            override suspend fun readFile(uri: String) = "file:$uri"
            override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "range"
            override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "search"
            override suspend fun searchSymbol(query: String, offset: Int) = "symbol"
            override suspend fun readIl2CppClass(className: String) = "class"
            override suspend fun readProtocolRecord(id: String) = "protocol"
            override suspend fun readSoSnapshot(endpoint: String?) = "snapshot"
            override suspend fun readDoc(id: String) = "doc"
        }
        val outcomes = ReadonlyAgentToolExecutor(source).executeTurn(listOf(
            AiToolCall(0, "call-a", "read_current_file", "{}"),
            AiToolCall(1, "call-b", "read_current_file", " { } ")
        ))

        assertEquals(1, reads)
        assertEquals(2, outcomes.size)
        val first = (outcomes[0] as AgentToolOutcome.Success).result
        val second = (outcomes[1] as AgentToolOutcome.Success).result
        assertEquals("call-a", first.callId)
        assertEquals("call-b", second.callId)
        assertEquals(first.resultId, second.resultId)
        assertTrue(first.content == second.content)
    }

    @Test fun doesNotDeduplicateDifferentPaginationArguments() {
        val calls = AiToolCallNormalizer.normalize(listOf(
            AiToolCall(0, "a", "read_tool_result", "{\"resultId\":\"r\",\"offset\":0,\"limit\":10}"),
            AiToolCall(1, "b", "read_tool_result", "{\"limit\":10,\"offset\":10,\"resultId\":\"r\"}")
        ))
        assertEquals(2, calls.map(AiToolCallNormalizer::semanticFingerprint).distinct().size)
    }
}
