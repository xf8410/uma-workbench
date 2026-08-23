package com.uma.workbench.github

import kotlinx.coroutines.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubContributionWorkflowTest {
    private class FakeGateway(private val fixture: GitHubForkBinding) : GitHubContributionGateway {
        val calls = mutableListOf<String>()
        override suspend fun fork(owner: String, repo: String, confirmationId: String?) = GitHubForkResult(fixture, "https://github.com/me/project", true).also { calls += "fork" }
        override suspend fun createWorkbenchBranch(binding: GitHubForkBinding, branch: String, confirmationId: String?) = GitHubContributionBranch(binding, branch, "base").also { calls += "branch" }
        override suspend fun writeFile(binding: GitHubForkBinding, branch: String, change: GitHubFileChange, commitMessage: String, confirmationId: String?) = GitRef(branch, change.path).also { calls += "write:${change.path}" }
        override suspend fun createCrossForkPullRequest(request: GitHubCrossForkPullRequestRequest) = GitHubContributionPullRequest(1, "https://github.com/upstream/project/pull/1", "open", "me", "project", request.headBranch, "upstream", "project", request.baseBranch).also { calls += "pr" }
    }

    private val binding = GitHubForkBinding("upstream", "project", "me", "project", "main", "main")

    @Test fun requiresOrderedStages() = runTest {
        val fake = FakeGateway(binding)
        val workflow = GitHubContributionWorkflow(fake)
        val progress = workflow.createFork("upstream", "project", "confirm")
        val withBranch = workflow.createBranch(progress, "workbench/fix", "confirm")
        val withCommit = workflow.commitFiles(withBranch, listOf(GitHubFileChange("README.md", "updated")), "docs: update", "confirm")
        val result = workflow.createPullRequest(withCommit, "docs: update", "body", true, "confirm")
        assertEquals(listOf("fork", "branch", "write:README.md", "pr"), fake.calls)
        assertEquals(1, result.pullRequest?.number)
    }

    @Test fun cannotCreatePrBeforeCommit() = runTest {
        val fake = FakeGateway(binding)
        val workflow = GitHubContributionWorkflow(fake)
        val progress = workflow.createBranch(workflow.createFork("upstream", "project", "confirm"), "workbench/fix", "confirm")
        val error = assertFailsWith<IllegalArgumentException> {
            workflow.createPullRequest(progress, "title", "body", false, "confirm")
        }
        assertEquals("请先提交至少一个文件变更", error.message)
        assertEquals(listOf("fork", "branch"), fake.calls)
    }
}
