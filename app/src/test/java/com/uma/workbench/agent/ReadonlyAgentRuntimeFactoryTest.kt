package com.uma.workbench.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadonlyAgentRuntimeFactoryTest {
    private val source = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "files"
        override suspend fun readCurrentFile() = "evidence"
        override suspend fun readFile(uri: String) = uri
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "range"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = query
        override suspend fun searchSymbol(query: String, offset: Int) = query
        override suspend fun readIl2CppClass(className: String) = className
        override suspend fun readProtocolRecord(id: String) = id
        override suspend fun readSoSnapshot(endpoint: String?) = endpoint ?: "latest"
        override suspend fun readDoc(id: String) = id
    }

    @Test fun assembledRootDelegatesWhileChildCannotSeeDelegationTool() = runBlocking {
        var rootRound = 0
        var childSawDelegation = true
        val provider = AiStreamingProvider { request -> flow {
            val isChild = request.messages.any { it.completeContent.contains("[sub_agent_task]") }
            if (isChild) {
                childSawDelegation = request.tools.toString().contains("delegate_subagents")
                emit(AiStreamEvent.TextDelta("child result"))
            } else {
                rootRound++
                if (rootRound == 1) emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(
                    0, "delegate", "delegate_subagents",
                    "{\"tasks\":[{\"id\":\"one\",\"instruction\":\"inspect\"}]}"
                ))) else emit(AiStreamEvent.TextDelta("root result"))
            }
            emit(AiStreamEvent.Completed)
        } }
        val loop = ReadonlyAgentRuntimeFactory(provider, source, InMemoryAgentToolResultStore()).createRootLoop()
        val result = loop.run(AiGenerationRequest(
            "root", listOf(AiPromptMessage("user", "q")), "m", ReadonlyAgentToolSchemas.openAiCompatible
        ))

        assertEquals("root result", result.completeAnswer)
        assertFalse(childSawDelegation)
        val delegation = result.rounds.first().toolOutcomes.single()
        assertTrue(delegation is AgentToolOutcome.Success)
        assertEquals("delegate_subagents", (delegation as AgentToolOutcome.Success).result.toolName)
    }
}
