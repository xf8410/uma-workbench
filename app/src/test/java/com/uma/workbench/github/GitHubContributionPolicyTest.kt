package com.uma.workbench.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubContributionPolicyTest {
    private val binding = GitHubForkBinding("upstream", "project", "me", "project", "main", "main")

    @Test fun crossForkHeadUsesForkOwnerPrefix() {
        assertEquals("me:workbench/fix-login", GitHubContributionPolicy.headRef(binding, "workbench/fix-login"))
    }

    @Test fun rejectsNonWorkbenchBranch() {
        assertThrows(IllegalArgumentException::class.java) { GitHubContributionPolicy.headRef(binding, "feature/fix") }
        assertThrows(IllegalArgumentException::class.java) { GitHubContributionBranch(binding, "main", "sha") }
    }

    @Test fun requiresExplicitConfirmation() {
        assertThrows(IllegalArgumentException::class.java) { GitHubContributionPolicy.requireConfirmation("create fork", null) }
    }
}
