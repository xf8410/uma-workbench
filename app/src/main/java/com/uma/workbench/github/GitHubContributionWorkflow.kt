package com.uma.workbench.github

/** Persistable progress snapshot for a single upstream contribution attempt. */
data class GitHubContributionProgress(
    val upstreamOwner: String,
    val upstreamRepo: String,
    val fork: GitHubForkResult? = null,
    val branch: GitHubContributionBranch? = null,
    val commits: List<GitRef> = emptyList(),
    val pullRequest: GitHubContributionPullRequest? = null,
    val error: String? = null
) {
    val readyForPullRequest: Boolean get() = branch != null && commits.isNotEmpty() && pullRequest == null
}

/**
 * Coordinates the remote mutation order. Each method requires the caller to provide
 * a confirmation token obtained from an explicit UI confirmation step.
 */
class GitHubContributionWorkflow(
    private val gateway: GitHubContributionGateway
) {
    suspend fun createFork(
        upstreamOwner: String,
        upstreamRepo: String,
        confirmationId: String
    ): GitHubContributionProgress {
        val fork = gateway.fork(upstreamOwner, upstreamRepo, confirmationId)
        return GitHubContributionProgress(upstreamOwner, upstreamRepo, fork = fork)
    }

    suspend fun createBranch(
        progress: GitHubContributionProgress,
        branch: String,
        confirmationId: String
    ): GitHubContributionProgress {
        val fork = requireNotNull(progress.fork) { "请先创建或选择 Fork" }
        val created = gateway.createWorkbenchBranch(fork.binding, branch, confirmationId)
        return progress.copy(branch = created, error = null)
    }

    suspend fun commitFiles(
        progress: GitHubContributionProgress,
        changes: List<GitHubFileChange>,
        commitMessage: String,
        confirmationId: String
    ): GitHubContributionProgress {
        require(changes.isNotEmpty()) { "至少需要一个文件变更" }
        require(commitMessage.isNotBlank()) { "提交信息不能为空" }
        val branch = requireNotNull(progress.branch) { "请先创建 workbench/* 分支" }
        val refs = changes.map { change ->
            gateway.writeFile(branch.binding, branch.branch, change, commitMessage, confirmationId)
        }
        return progress.copy(commits = progress.commits + refs, error = null)
    }

    suspend fun createPullRequest(
        progress: GitHubContributionProgress,
        title: String,
        body: String,
        draft: Boolean,
        confirmationId: String
    ): GitHubContributionProgress {
        val branch = requireNotNull(progress.branch) { "请先创建 workbench/* 分支" }
        require(progress.commits.isNotEmpty()) { "请先提交至少一个文件变更" }
        val request = GitHubCrossForkPullRequestRequest(
            binding = branch.binding,
            headBranch = branch.branch,
            title = title,
            body = body,
            draft = draft,
            confirmationId = confirmationId
        )
        val pullRequest = gateway.createCrossForkPullRequest(request)
        return progress.copy(pullRequest = pullRequest, error = null)
    }
}
