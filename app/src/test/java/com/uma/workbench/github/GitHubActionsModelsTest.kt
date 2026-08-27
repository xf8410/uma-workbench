package com.uma.workbench.github

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionsModelsTest {
    @Test fun onlyLiveStatusesAreCancellableCandidates() {
        listOf("queued", "in_progress", "pending", "waiting").forEach { status ->
            assertTrue(run(status, null).active)
        }
        assertFalse(run("completed", "success").active)
        assertFalse(run("completed", "cancelled").active)
    }

    @Test fun cancellationTargetRequiresPinnedHeadSha() {
        var failed = false
        try {
            GitHubActionsCancellationTarget("xf8410", "repo", 1, "short", "CI")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private fun run(status: String, conclusion: String?) = GitHubActionsRun(
        id = 1,
        repository = "xf8410/repo",
        name = "Android CI",
        event = "push",
        status = status,
        conclusion = conclusion,
        headBranch = "workbench/test",
        headSha = "0123456789abcdef",
        runAttempt = 1,
        htmlUrl = "https://github.com/xf8410/repo/actions/runs/1",
        createdAt = null,
        updatedAt = null
    )
}
