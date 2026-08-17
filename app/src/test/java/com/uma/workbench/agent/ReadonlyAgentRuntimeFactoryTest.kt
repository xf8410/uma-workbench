package com.uma.workbench.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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

    private open class FakeGitHub : GitHubReadonlyAgentToolDataSource {
        val calls = mutableListOf<String>()
        override suspend fun listRepositories(page: Int) = "github-repositories-page-$page".also { calls += it }
        override suspend fun getRepository(owner: String, name: String) = "$owner/$name"
        override suspend fun listBranches(owner: String, name: String) = "branches"
        override suspend fun readFile(owner: String, name: String, ref: String, path: String) = "file"
        override suspend fun listCommits(owner: String, name: String, ref: String, page: Int) = "commits"
        override suspend fun getWorkflowRuns(owner: String, name: String, page: Int) = "runs"
    }

    @Test fun assembledRootDelegatesWhileChildCannotSeeDelegationOrGitHubTools() = runBlocking {
        var rootRound = 0
        var childSawDelegation = true
        var childSawGitHub = true
        val provider = AiStreamingProvider { request -> flow {
            val isChild = request.messages.any { it.completeContent.contains("[sub_agent_task]") }
            if (isChild) {
                childSawDelegation = request.tools.toString().contains("delegate_subagents")
                childSawGitHub = request.tools.toString().contains("github_list_repositories")
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
        val loop = ReadonlyAgentRuntimeFactory(
            provider, source, InMemoryAgentToolResultStore(), githubSource = FakeGitHub()
        ).createRootLoop()
        val result = loop.run(AiGenerationRequest(
            "root", listOf(AiPromptMessage("user", "q")), "m", ReadonlyAgentToolSchemas.openAiCompatible
        ))

        assertEquals("root result", result.completeAnswer)
        assertFalse(childSawDelegation)
        assertFalse(childSawGitHub)
        val delegation = result.rounds.first().toolOutcomes.single()
        assertTrue(delegation is AgentToolOutcome.Success)
        assertEquals("delegate_subagents", (delegation as AgentToolOutcome.Success).result.toolName)
    }

    @Test fun configuredMainAgentExecutesGitHubToolEndToEnd() = runBlocking {
        val github = FakeGitHub()
        var round = 0
        var secondRoundSawEvidence = false
        val provider = AiStreamingProvider { request -> flow {
            round++
            if (round == 1) {
                assertTrue(request.tools.toString().contains("github_list_repositories"))
                emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(
                    0, "github-call", "github_list_repositories", "{}"
                )))
            } else {
                secondRoundSawEvidence = request.messages.any {
                    it.role == "tool" && it.toolName == "github_list_repositories" &&
                        it.completeContent.contains("github-repositories-page-1")
                }
                emit(AiStreamEvent.TextDelta("done"))
            }
            emit(AiStreamEvent.Completed)
        } }
        val result = ReadonlyAgentRuntimeFactory(
            provider, source, InMemoryAgentToolResultStore(), githubSource = github
        ).createRootLoop().run(AiGenerationRequest(
            "main", listOf(AiPromptMessage("user", "list repos")), "m",
            ReadonlyAgentToolSchemas.openAiCompatible
        ))

        assertEquals("done", result.completeAnswer)
        assertEquals(listOf("github-repositories-page-1"), github.calls)
        assertTrue(secondRoundSawEvidence)
        assertTrue(result.rounds.first().toolOutcomes.single() is AgentToolOutcome.Success)
    }

    @Test fun unconfiguredMainAgentReturnsExplicitGitHubFailure() = runBlocking {
        var round = 0
        var sawConfigurationError = false
        val provider = AiStreamingProvider { request -> flow {
            round++
            if (round == 1) {
                emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(
                    0, "github-call", "github_list_repositories", "{}"
                )))
            } else {
                sawConfigurationError = request.messages.any {
                    it.role == "tool" && it.completeContent.contains("GitHub 只读工具未配置")
                }
                emit(AiStreamEvent.TextDelta("handled"))
            }
            emit(AiStreamEvent.Completed)
        } }
        val result = ReadonlyAgentRuntimeFactory(
            provider, source, InMemoryAgentToolResultStore()
        ).createRootLoop().run(AiGenerationRequest(
            "main", listOf(AiPromptMessage("user", "list repos")), "m",
            ReadonlyAgentToolSchemas.openAiCompatible
        ))

        assertEquals("handled", result.completeAnswer)
        assertTrue(sawConfigurationError)
        assertTrue(result.rounds.first().toolOutcomes.single() is AgentToolOutcome.Failure)
    }
}
