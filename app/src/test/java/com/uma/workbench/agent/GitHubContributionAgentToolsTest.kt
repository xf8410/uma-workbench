package com.uma.workbench.agent

import com.uma.workbench.github.GitHubContributionBranch
import com.uma.workbench.github.GitHubContributionPullRequest
import com.uma.workbench.github.GitHubContributionProgress
import com.uma.workbench.github.GitHubForkBinding
import com.uma.workbench.github.GitHubForkResult
import com.uma.workbench.github.GitRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubContributionAgentToolsTest {
    private val binding = GitHubForkBinding("upstream", "project", "me", "project", "main", "main")

    @Test fun progressJsonRoundTripsThroughEveryStage() {
        val progress = GitHubContributionProgress(
            upstreamOwner = "upstream",
            upstreamRepo = "project",
            fork = GitHubForkResult(binding, "https://github.com/me/project", true),
            branch = GitHubContributionBranch(binding, "workbench/fix", "abc123"),
            commits = listOf(GitRef("workbench/fix", "file-sha-1"), GitRef("workbench/fix", "file-sha-2")),
            pullRequest = GitHubContributionPullRequest(
                7, "https://github.com/upstream/project/pull/7", "open",
                "me", "project", "workbench/fix", "upstream", "project", "main"
            )
        )
        val decoded = GitHubContributionProgressCodec.decode(GitHubContributionProgressCodec.encode(progress))
        assertEquals(progress, decoded)
    }

    @Test fun progressDecodingToleratesMissingOptionalStages() {
        val decoded = GitHubContributionProgressCodec.decode("""{"upstreamOwner":"u","upstreamRepo":"p"}""")
        assertEquals("u", decoded.upstreamOwner)
        assertEquals("p", decoded.upstreamRepo)
        assertNull(decoded.fork)
        assertNull(decoded.branch)
        assertNull(decoded.pullRequest)
        assertTrue(decoded.commits.isEmpty())
    }

    @Test fun progressDecodingRejectsBlankOwner() {
        var threw = false
        try {
            GitHubContributionProgressCodec.decode("""{"upstreamOwner":"","upstreamRepo":"p"}""")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun renderersShowActionableSummary() {
        val fork = GitHubForkResult(binding, "https://github.com/me/project", true)
        val branch = GitHubContributionBranch(binding, "workbench/fix", "abc123")
        val pr = GitHubContributionPullRequest(
            7, "https://github.com/upstream/project/pull/7", "open",
            "me", "project", "workbench/fix", "upstream", "project", "main"
        )
        val forkText = GitHubContributionToolRenderer.fork(fork, "{}")
        val branchText = GitHubContributionToolRenderer.branch(branch, "{}")
        val commitText = GitHubContributionToolRenderer.commit(GitRef("workbench/fix", "sha"), "README.md", "{}", 1)
        val prText = GitHubContributionToolRenderer.pullRequest(pr, "{}")

        assertTrue(forkText.contains("https://github.com/me/project"))
        assertTrue(branchText.contains("workbench/fix"))
        assertTrue(commitText.contains("README.md"))
        assertTrue(prText.contains("pull/7"))
    }

    @Test fun rendererKeepsProgressPayloadForNextStage() {
        val progress = GitHubContributionProgress("upstream", "project")
        val encoded = GitHubContributionProgressCodec.encode(progress)
        val text = GitHubContributionToolRenderer.fork(
            GitHubForkResult(binding, null, true), encoded
        )
        assertTrue("渲染输出应携带完整 progress JSON 供下一步使用", text.contains(encoded))
    }
}
