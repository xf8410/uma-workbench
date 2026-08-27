package com.uma.workbench.github

/** Explicitly binds a writable fork to its upstream repository. */
data class GitHubForkBinding(
    val upstreamOwner: String,
    val upstreamRepo: String,
    val forkOwner: String,
    val forkRepo: String,
    val upstreamDefaultBranch: String,
    val forkDefaultBranch: String
) {
    init {
        require(upstreamOwner.isNotBlank() && upstreamRepo.isNotBlank())
        require(forkOwner.isNotBlank() && forkRepo.isNotBlank())
        require(upstreamOwner != forkOwner || upstreamRepo != forkRepo) { "Fork 必须与上游仓库不同" }
    }
}

data class GitHubForkResult(
    val binding: GitHubForkBinding,
    val htmlUrl: String?,
    val ready: Boolean
)

data class GitHubContributionBranch(
    val binding: GitHubForkBinding,
    val branch: String,
    val headSha: String
) {
    init { require(branch.startsWith("workbench/")) { "贡献分支必须使用 workbench/* 前缀" } }
}

data class GitHubFileChange(
    val path: String,
    val content: String,
    val expectedFileSha: String? = null
) { init { require(path.isNotBlank() && !path.startsWith("/")) { "文件路径无效" } } }

data class GitHubCrossForkPullRequestRequest(
    val binding: GitHubForkBinding,
    val headBranch: String,
    val baseBranch: String = binding.upstreamDefaultBranch,
    val title: String,
    val body: String,
    val draft: Boolean = false,
    val confirmationId: String? = null
) {
    init {
        require(headBranch.startsWith("workbench/")) { "PR 来源分支必须使用 workbench/* 前缀" }
        require(baseBranch.isNotBlank() && title.isNotBlank())
    }
}

data class GitHubContributionPullRequest(
    val number: Int,
    val htmlUrl: String,
    val state: String,
    val headOwner: String,
    val headRepo: String,
    val headBranch: String,
    val baseOwner: String,
    val baseRepo: String,
    val baseBranch: String
)

/** API boundary for the remote Fork -> branch -> commit -> upstream PR flow. */
interface GitHubContributionGateway {
    suspend fun fork(owner: String, repo: String, confirmationId: String?): GitHubForkResult
    suspend fun createWorkbenchBranch(binding: GitHubForkBinding, branch: String, confirmationId: String?): GitHubContributionBranch
    suspend fun writeFile(binding: GitHubForkBinding, branch: String, change: GitHubFileChange, commitMessage: String, confirmationId: String?): GitRef
    suspend fun createCrossForkPullRequest(request: GitHubCrossForkPullRequestRequest): GitHubContributionPullRequest
}

object GitHubContributionPolicy {
    fun requireConfirmation(operation: String, confirmationId: String?) {
        require(!confirmationId.isNullOrBlank()) { "远程贡献操作必须先确认：$operation" }
    }

    fun headRef(binding: GitHubForkBinding, branch: String): String {
        require(branch.startsWith("workbench/")) { "来源分支必须使用 workbench/* 前缀" }
        return "${binding.forkOwner}:$branch"
    }

    fun validateTarget(request: GitHubCrossForkPullRequestRequest) {
        require(request.binding.upstreamOwner.isNotBlank())
        require(request.binding.upstreamRepo.isNotBlank())
        require(request.baseBranch == request.binding.upstreamDefaultBranch || request.baseBranch.isNotBlank())
        require(request.headBranch.startsWith("workbench/"))
    }
}
