package com.uma.workbench.ui

import com.uma.workbench.agent.*
import java.nio.file.Files
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatRuntimeFactoryTest {
    private fun workspace() = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "workspace"
        override suspend fun readCurrentFile() = "current"
        override suspend fun readFile(uri: String) = uri
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "range"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = query
        override suspend fun searchSymbol(query: String, offset: Int) = query
        override suspend fun readIl2CppClass(className: String) = className
        override suspend fun readProtocolRecord(id: String) = id
        override suspend fun readSoSnapshot(endpoint: String?) = endpoint ?: "summary"
        override suspend fun readDoc(id: String) = id
    }

    @Test fun chatCompositionPassesGitHubEvidenceThroughMainAgent() = runBlocking {
        val calls = mutableListOf<Int>()
        val github = object : GitHubReadonlyAgentToolDataSource {
            override suspend fun listRepositories(page: Int) = "android-github-$page".also { calls += page }
            override suspend fun getRepository(owner: String, name: String) = "repo"
            override suspend fun listBranches(owner: String, name: String) = "branches"
            override suspend fun readFile(owner: String, name: String, ref: String, path: String) = "file"
            override suspend fun listCommits(owner: String, name: String, ref: String, page: Int) = "commits"
            override suspend fun getWorkflowRuns(owner: String, name: String, page: Int) = "runs"
        }
        var round = 0
        var sawToolEvidence = false
        val provider = AiStreamingProvider { request -> flow {
            round++
            if (round == 1) {
                emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(
                    0, "gh", "github_list_repositories", "{}"
                )))
            } else {
                sawToolEvidence = request.messages.any {
                    it.role == "tool" && it.completeContent.contains("android-github-1")
                }
                emit(AiStreamEvent.TextDelta("complete"))
            }
            emit(AiStreamEvent.Completed)
        } }
        val root = Files.createTempDirectory("chat-runtime").toFile()
        try {
            val result = AiChatRuntimeFactory.create(
                root, provider, "workspace", "conversation", github, workspace()
            ).run(AiGenerationRequest(
                "request", listOf(AiPromptMessage("user", "repositories")), "model",
                ReadonlyAgentToolSchemas.openAiCompatible
            ))
            assertEquals("complete", result.completeAnswer)
            assertEquals(listOf(1), calls)
            assertTrue(sawToolEvidence)
            assertTrue(root.resolve("agent-tool-results/workspace/conversation").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
