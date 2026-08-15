package com.uma.workbench.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadonlyAgentLoopTest {
    @Test fun executesToolAndReturnsSecondRoundAnswer() = runBlocking {
        val requests = mutableListOf<AiGenerationRequest>()
        val provider = AiStreamingProvider { request -> flow {
            requests += request
            if (requests.size == 1) {
                emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0, "call-1", "read_current_file", "{}")))
                emit(AiStreamEvent.Usage(AiTokenUsage(3, 1, 4)))
            } else {
                emit(AiStreamEvent.TextDelta("基于文件的最终回答"))
                emit(AiStreamEvent.Usage(AiTokenUsage(7, 5, 12)))
            }
            emit(AiStreamEvent.Completed)
        } }
        val source = object : ReadonlyAgentToolDataSource {
            override suspend fun listWorkspaceFiles() = error("unused")
            override suspend fun readCurrentFile() = "完整文件正文"
            override suspend fun readFile(uri: String) = error("unused")
            override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = error("unused")
            override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = error("unused")
            override suspend fun searchSymbol(query: String, offset: Int) = error("unused")
            override suspend fun readIl2CppClass(className: String) = error("unused")
            override suspend fun readProtocolRecord(id: String) = error("unused")
            override suspend fun readSoSnapshot(endpoint: String?) = error("unused")
            override suspend fun readDoc(id: String) = error("unused")
        }
        val result = ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)).run(
            AiGenerationRequest("r", listOf(AiPromptMessage("user", "分析当前文件")), "m")
        )

        assertEquals("基于文件的最终回答", result.completeAnswer)
        assertEquals(2, requests.size)
        assertEquals("assistant", requests[1].messages[1].role)
        assertEquals("tool", requests[1].messages[2].role)
        assertEquals("call-1", requests[1].messages[2].toolCallId)
        assertTrue(requests[1].messages[2].completeContent.contains("完整文件正文"))
        assertEquals(16L, result.usage.totalTokens)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyFinalAnswerCannotSucceed() {
        runBlocking {
            val provider = AiStreamingProvider { flow { emit(AiStreamEvent.Completed) } }
            val source = object : ReadonlyAgentToolDataSource {
                override suspend fun listWorkspaceFiles() = "x"
                override suspend fun readCurrentFile() = "x"
                override suspend fun readFile(uri: String) = "x"
                override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "x"
                override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = "x"
                override suspend fun searchSymbol(query: String, offset: Int) = "x"
                override suspend fun readIl2CppClass(className: String) = "x"
                override suspend fun readProtocolRecord(id: String) = "x"
                override suspend fun readSoSnapshot(endpoint: String?) = "x"
                override suspend fun readDoc(id: String) = "x"
            }
            ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)).run(AiGenerationRequest("r", listOf(AiPromptMessage("user", "q")), "m"))
        }
    }
}
