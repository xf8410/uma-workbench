package com.uma.workbench.github

/** Read operations are safe by default. Remote mutations require explicit user confirmation. */
interface GitHubGateway {
    suspend fun repositories(page: Int): List<GitHubRepositorySummary>
    suspend fun repository(owner: String, name: String): GitHubRepositorySummary
    suspend fun branches(owner: String, name: String): List<GitRef>
    suspend fun tags(owner: String, name: String): List<GitRef>
    suspend fun commits(owner: String, name: String, ref: String, page: Int): List<GitCommitSummary>
    suspend fun directory(owner: String, name: String, ref: String, path: String): List<GitContent>
    suspend fun issues(owner: String, name: String, page: Int): List<GitIssueSummary>
    suspend fun pullRequests(owner: String, name: String, page: Int): List<GitPullRequestSummary>
    suspend fun workflowRuns(owner: String, name: String, page: Int): List<WorkflowRunSummary>
    suspend fun artifacts(owner: String, name: String, runId: Long): List<ArtifactSummary>
}

data class GitHubRepositorySummary(val id: Long, val owner: String, val name: String, val description: String?, val defaultBranch: String, val isPrivate: Boolean, val isFork: Boolean, val isArchived: Boolean, val parent: String?, val pushedAt: String?)
data class GitRef(val name: String, val sha: String)
data class GitCommitSummary(val sha: String, val message: String, val author: String?, val committedAt: String?)
data class GitContent(val path: String, val type: String, val size: Long, val sha: String)
data class GitIssueSummary(val number: Int, val title: String, val state: String, val updatedAt: String?)
data class GitPullRequestSummary(val number: Int, val title: String, val state: String, val draft: Boolean, val merged: Boolean)
data class WorkflowRunSummary(val id: Long, val name: String, val status: String, val conclusion: String?, val headSha: String)
data class ArtifactSummary(val id: Long, val name: String, val size: Long, val expired: Boolean)
