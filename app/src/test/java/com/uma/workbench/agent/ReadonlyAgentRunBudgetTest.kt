package com.uma.workbench.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadonlyAgentRunBudgetTest {
    private fun source(read: suspend () -> String = { "content" }) = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "files"
        override suspend fun readCurrentFile() = read()
        override suspend fun readFile(uri: String) = "file:$uri"
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "$uri:$startLine-$endLine"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "$query:$offset:$caseSensitive"
        override suspend fun searchSymbol(query: String, offset: Int) = "$query:$offset"
        override suspend fun readIl2CppClass(className: String) = className
        override suspend fun readProtocolRecord(id: String) = id
        override suspend fun readSoSnapshot(endpoint: String?) = endpoint ?: "latest"
        override suspend fun readDoc(id: String) = id
    }

    @Test fun reusesEquivalentReadAcrossModelRoundsWithinOneRun() = runBlocking {
        var modelRound = 0
        var reads = 0
        val provider = AiStreamingProvider { flow {
            modelRound++
            when (modelRound) {
                1 -> emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0, "first", "read_current_file", "{}")))
                2 -> emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0, "second", "read_current_file", " { } ")))
                else -> emit(AiStreamEvent.TextDelta("完成"))
            }
            emit(AiStreamEvent.Completed)
        } }
        val result = ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source { reads++; "same" })).run(
            AiGenerationRequest("run", listOf(AiPromptMessage("user", "q")), "model")
        )

        assertEquals(1, reads)
        assertEquals(3, result.rounds.size)
        val first = (result.rounds[0].toolOutcomes.single() as AgentToolOutcome.Success).result
        val second = (result.rounds[1].toolOutcomes.single() as AgentToolOutcome.Success).result
        assertEquals("first", first.callId)
        assertEquals("second", second.callId)
        assertEquals(first.resultId, second.resultId)
    }

    @Test fun changedArgumentsCauseNewExecution() = runBlocking {
        var modelRound = 0
        var reads = 0
        val data = object : ReadonlyAgentToolDataSource by source() {
            override suspend fun readFile(uri: String): String { reads++; return uri }
        }
        val provider = AiStreamingProvider { flow {
            modelRound++
            if (modelRound <= 2) {
                val uri = if (modelRound == 1) "a" else "b"
                emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0, "c$modelRound", "read_file", "{\"uri\":\"$uri\"}")))
            } else emit(AiStreamEvent.TextDelta("完成"))
            emit(AiStreamEvent.Completed)
        } }
        ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(data)).run(
            AiGenerationRequest("run", listOf(AiPromptMessage("user", "q")), "model")
        )
        assertEquals(2, reads)
    }

    @Test fun rejectsExecutionBeyondWholeRunBudgetBeforeSecondRead() = runBlocking {
        var modelRound = 0
        var reads = 0
        val data = object : ReadonlyAgentToolDataSource by source() {
            override suspend fun readFile(uri: String): String { reads++; return uri }
        }
        val provider = AiStreamingProvider { flow {
            modelRound++
            val uri = if (modelRound == 1) "a" else "b"
            emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0, "c$modelRound", "read_file", "{\"uri\":\"$uri\"}")))
            emit(AiStreamEvent.Completed)
        } }
        val failure = runCatching {
            ReadonlyAgentLoop(
                provider,
                ReadonlyAgentToolExecutor(data),
                ReadonlyAgentLoopLimits(maxToolExecutionsPerRun = 1)
            ).run(AiGenerationRequest("run", listOf(AiPromptMessage("user", "q")), "model"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("总上限"))
        assertEquals(1, reads)
    }

    @Test fun stopsRepeatedCachedOnlyRoundsWithoutNewEvidence() = runBlocking {
        var call = 0
        val provider = AiStreamingProvider { flow {
            call++
            emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0, "c$call", "read_current_file", "{}")))
            emit(AiStreamEvent.Completed)
        } }
        val failure = runCatching {
            ReadonlyAgentLoop(
                provider,
                ReadonlyAgentToolExecutor(source()),
                ReadonlyAgentLoopLimits(maxConsecutiveCachedOnlyRounds = 1)
            ).run(AiGenerationRequest("run", listOf(AiPromptMessage("user", "q")), "model"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("未产生新证据"))
    }
}
