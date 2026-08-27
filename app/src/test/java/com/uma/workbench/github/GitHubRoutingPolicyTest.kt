package com.uma.workbench.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubRoutingPolicyTest {
    private val forkPolicy = GitHubRepositoryRoutingPolicy(
        owner = "xf8410",
        repo = "umaai-rs",
        upstreamOwner = "xulai1001",
        upstreamRepo = "umaai-rs",
        branchRules = listOf(
            GitHubBranchRule("master", GitHubBranchRole.STABLE_DATA, workflowsAllowed = false),
            GitHubBranchRule("ramen", GitHubBranchRole.DEVELOPMENT, workflowsAllowed = true),
            GitHubBranchRule("workbench/*", GitHubBranchRole.TASK, workflowsAllowed = true, preferredBase = "ramen")
        )
    )
    private val upstreamPolicy = GitHubRepositoryRoutingPolicy(
        owner = "xulai1001",
        repo = "umaai-rs",
        branchRules = listOf(
            GitHubBranchRule("master", GitHubBranchRole.STABLE_DATA, workflowsAllowed = false),
            GitHubBranchRule("ramen", GitHubBranchRole.DEVELOPMENT, workflowsAllowed = false)
        )
    )

    @Test fun acceptsForkCiAndExplicitRamenTarget() {
        val review = review(
            targetBranch = "ramen",
            ciOwner = "xf8410",
            ciRef = "ramen",
            canCancel = true,
            changedPaths = listOf("crates/umasim/src/game/ramen/state.rs")
        )
        assertTrue(review.allowed)
        assertEquals(GitHubBranchRole.TASK, review.sourceRole)
        assertEquals(GitHubBranchRole.DEVELOPMENT, review.targetRole)
    }

    @Test fun rejectsUpstreamCiEvenWhenTargetPrIsValid() {
        val review = review(
            targetBranch = "ramen",
            ciOwner = "xulai1001",
            ciRef = "ramen",
            canCancel = false
        )
        assertFalse(review.allowed)
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.CI_NOT_IN_SOURCE_FORK })
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.CI_CANNOT_CANCEL })
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.CI_ROLE_UNKNOWN })
    }

    @Test fun rejectsWorkflowFilesEnteringWorkflowFreeTarget() {
        val review = review(
            targetBranch = "ramen",
            ciOwner = "xf8410",
            ciRef = "ramen",
            canCancel = true,
            changedPaths = listOf(".github/workflows/huge-matrix.yml", "crates/umasim/src/lib.rs")
        )
        assertFalse(review.allowed)
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.WORKFLOW_NOT_ALLOWED_ON_TARGET })
    }

    @Test fun rejectsMasterChosenByDefaultForRamenTask() {
        val review = review(
            targetBranch = "master",
            ciOwner = "xf8410",
            ciRef = "ramen",
            canCancel = true
        )
        assertFalse(review.allowed)
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.SOURCE_BASE_ROLE_MISMATCH })
    }

    @Test fun unknownBranchNeverFallsBackToDefaultBranch() {
        val route = GitHubPullRequestRoute(
            "xf8410", "umaai-rs", "feature/unnamed",
            "xulai1001", "umaai-rs", "ramen",
            "xf8410", "umaai-rs", "ramen", true
        )
        val review = GitHubRoutingGuard.review(route, forkPolicy, upstreamPolicy)
        assertEquals(GitHubBranchRole.UNKNOWN, review.sourceRole)
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.SOURCE_ROLE_UNKNOWN })
    }

    @Test fun activeUpstreamDispatchIsAlwaysRejected() {
        val review = GitHubRoutingGuard.review(
            GitHubPullRequestRoute(
                "xf8410", "umaai-rs", "workbench/fix",
                "xulai1001", "umaai-rs", "ramen",
                "xf8410", "umaai-rs", "ramen", true,
                dispatchUpstreamWorkflow = true
            ),
            forkPolicy,
            upstreamPolicy
        )
        assertTrue(review.blocks.any { it.code == GitHubRouteBlockCode.UPSTREAM_WORKFLOW_DISPATCH })
        try {
            review.requireAllowed()
            fail("上游 dispatch 路由必须被阻止")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("UPSTREAM_WORKFLOW_DISPATCH"))
        }
    }

    private fun review(
        targetBranch: String,
        ciOwner: String,
        ciRef: String,
        canCancel: Boolean,
        changedPaths: List<String> = emptyList()
    ): GitHubRouteReview = GitHubRoutingGuard.review(
        GitHubPullRequestRoute(
            sourceOwner = "xf8410",
            sourceRepo = "umaai-rs",
            sourceBranch = "workbench/fix",
            targetOwner = "xulai1001",
            targetRepo = "umaai-rs",
            targetBranch = targetBranch,
            ciOwner = ciOwner,
            ciRepo = "umaai-rs",
            ciRef = ciRef,
            canCancelCi = canCancel,
            changedPaths = changedPaths
        ),
        forkPolicy,
        upstreamPolicy
    )
}
