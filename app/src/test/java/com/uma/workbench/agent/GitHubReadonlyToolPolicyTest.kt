package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadonlyToolPolicyTest {
    private val githubTools = setOf(
        "github_list_repositories",
        "github_get_repository",
        "github_list_branches",
        "github_read_file",
        "github_list_commits",
        "github_get_workflow_runs"
    )

    @Test fun allSixReadOnlyGitHubToolsAreAllowed() {
        assertTrue(ReadonlyAgentToolPolicy.allowedNames.containsAll(githubTools))
    }

    @Test fun onlyRepositoryListMayOmitArguments() {
        val normalized = AiToolCallNormalizer.normalize(
            listOf(AiToolCall(0, "list", "github_list_repositories", ""))
        ).single()
        assertEquals("{}", normalized.completeArgumentsJson)
        assertTrue("github_get_repository" !in ReadonlyAgentToolPolicy.toolsAllowingEmptyArguments)
        assertTrue("github_list_branches" !in ReadonlyAgentToolPolicy.toolsAllowingEmptyArguments)
        assertTrue("github_read_file" !in ReadonlyAgentToolPolicy.toolsAllowingEmptyArguments)
        assertTrue("github_list_commits" !in ReadonlyAgentToolPolicy.toolsAllowingEmptyArguments)
        assertTrue("github_get_workflow_runs" !in ReadonlyAgentToolPolicy.toolsAllowingEmptyArguments)
    }

    @Test fun policyAcceptsObjectArgumentsForEveryGitHubTool() {
        githubTools.forEachIndexed { index, name ->
            val arguments = if (name == "github_list_repositories") "{}" else "{\"owner\":\"o\",\"name\":\"r\"}"
            val result = ReadonlyAgentToolPolicy.validate(AiToolCall(index, "id-$index", name, arguments))
            assertTrue(result is kotlinx.serialization.json.JsonObject)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun mutatingGitHubWriteFileRemainsForbidden() {
        ReadonlyAgentToolPolicy.validate(
            AiToolCall(0, "write", "github_write_file", "{\"path\":\"a.kt\"}")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun mutatingGitHubCreateBranchRemainsForbidden() {
        ReadonlyAgentToolPolicy.validate(
            AiToolCall(0, "branch", "github_create_branch", "{\"branch\":\"workbench/test\"}")
        )
    }
}
