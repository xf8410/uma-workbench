package com.uma.workbench.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadonlyToolExecutorTest {
    private fun workspace() = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "workspace"
        override suspend fun readCurrentFile() = "current"
        override suspend fun readFile(uri: String) = uri
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "$uri:$startLine-$endLine"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = query
        override suspend fun searchSymbol(query: String, offset: Int) = query
        override suspend fun readIl2CppClass(className: String) = className
        override suspend fun readProtocolRecord(id: String) = id
        override suspend fun readSoSnapshot(endpoint: String?) = endpoint ?: "/summary"
        override suspend fun readDoc(id: String) = id
    }

    private class FakeGitHub : GitHubReadonlyAgentToolDataSource {
        val invocations = mutableListOf<String>()
        override suspend fun listRepositories(page: Int) = "repos:$page".also { invocations += it }
        override suspend fun getRepository(owner: String, name: String) = "repo:$owner/$name".also { invocations += it }
        override suspend fun listBranches(owner: String, name: String) = "branches:$owner/$name".also { invocations += it }
        override suspend fun readFile(owner: String, name: String, ref: String, path: String) = "file:$owner/$name@$ref:$path".also { invocations += it }
        override suspend fun listCommits(owner: String, name: String, ref: String, page: Int) = "commits:$owner/$name@$ref:$page".also { invocations += it }
        override suspend fun getWorkflowRuns(owner: String, name: String, page: Int) = "runs:$owner/$name:$page".also { invocations += it }
    }

    @Test fun dispatchesAllSixGitHubToolsWithDefaultsAndRootPath() = runBlocking {
        val github = FakeGitHub()
        val executor = ReadonlyAgentToolExecutor(workspace(), githubSource = github)
        val calls = listOf(
            AiToolCall(0, "a", "github_list_repositories", "{}"),
            AiToolCall(1, "b", "github_get_repository", "{\"owner\":\"o\",\"name\":\"r\"}"),
            AiToolCall(2, "c", "github_list_branches", "{\"owner\":\"o\",\"name\":\"r\"}"),
            AiToolCall(3, "d", "github_read_file", "{\"owner\":\"o\",\"name\":\"r\",\"ref\":\"main\",\"path\":\"\"}"),
            AiToolCall(4, "e", "github_list_commits", "{\"owner\":\"o\",\"name\":\"r\",\"ref\":\"main\"}"),
            AiToolCall(5, "f", "github_get_workflow_runs", "{\"owner\":\"o\",\"name\":\"r\",\"page\":2}")
        )
        val outcomes = executor.executeTurn(calls)
        assertTrue(outcomes.all { it is AgentToolOutcome.Success })
        assertEquals(listOf(
            "repos:1", "repo:o/r", "branches:o/r", "file:o/r@main:",
            "commits:o/r@main:1", "runs:o/r:2"
        ), github.invocations)
    }

    @Test fun missingGitHubSourceIsVisibleFailureAndDoesNotTouchWorkspace() = runBlocking {
        val outcome = ReadonlyAgentToolExecutor(workspace()).execute(
            AiToolCall(0, "a", "github_list_repositories", "{}")
        )
        val failure = (outcome as AgentToolOutcome.Failure).failure
        assertTrue(failure.completeError.contains("GitHub 只读工具未配置"))
    }

    @Test fun invalidZeroPageIsRejectedBeforeGitHubInvocation() = runBlocking {
        val github = FakeGitHub()
        val outcome = ReadonlyAgentToolExecutor(workspace(), githubSource = github).execute(
            AiToolCall(0, "a", "github_list_repositories", "{\"page\":0}")
        )
        assertTrue(outcome is AgentToolOutcome.Failure)
        assertTrue(github.invocations.isEmpty())
    }

    @Test fun GitHubResultsUseExistingPagedResultStore() = runBlocking {
        val github = object : GitHubReadonlyAgentToolDataSource by FakeGitHub() {
            override suspend fun listRepositories(page: Int) = "0123456789"
        }
        val executor = ReadonlyAgentToolExecutor(
            workspace(), AgentToolExecutionLimits(pageCharacters = 4), githubSource = github
        )
        val first = (executor.execute(AiToolCall(0, "a", "github_list_repositories", "{}")) as AgentToolOutcome.Success).result
        assertEquals("0123", first.content)
        assertFalse(first.complete)
        assertEquals(4, first.nextOffset)
        val second = (executor.execute(AiToolCall(1, "b", "read_tool_result", "{\"resultId\":\"${first.resultId}\",\"offset\":4}")) as AgentToolOutcome.Success).result
        assertEquals("4567", second.content)
        assertEquals(first.sha256, second.sha256)
    }
}
