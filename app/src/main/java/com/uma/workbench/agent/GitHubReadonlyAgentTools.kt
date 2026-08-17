package com.uma.workbench.agent

import android.content.Context
import com.uma.workbench.github.GitContent
import com.uma.workbench.github.GitHubApiClient
import com.uma.workbench.github.GitHubCredentialStore
import com.uma.workbench.github.GitHubFileContent
import com.uma.workbench.github.GitHubRepositorySummary
import com.uma.workbench.github.GitCommitSummary
import com.uma.workbench.github.GitRef
import com.uma.workbench.github.WorkflowRunSummary
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface GitHubReadonlyAgentToolDataSource {
    suspend fun listRepositories(page: Int): String
    suspend fun getRepository(owner: String, name: String): String
    suspend fun listBranches(owner: String, name: String): String
    suspend fun readFile(owner: String, name: String, ref: String, path: String): String
    suspend fun listCommits(owner: String, name: String, ref: String, page: Int): String
    suspend fun getWorkflowRuns(owner: String, name: String, page: Int): String
}

/** Pure renderer kept independent from Android and network code so bounds and redaction are unit-testable. */
internal object GitHubReadonlyToolRenderer {
    const val MAX_FILE_BYTES = 750_000L
    const val MAX_DIRECTORY_ENTRIES = 500

    fun repositories(page: Int, values: List<GitHubRepositorySummary>) = buildJsonObject {
        put("page", page.coerceAtLeast(1)); put("repositories", buildJsonArray { values.forEach { repository ->
            add(repository(repository))
        } })
    }.toString()

    fun repository(value: GitHubRepositorySummary) = repository(value).toString()

    fun branches(values: List<GitRef>) = buildJsonObject {
        put("branches", buildJsonArray { values.take(100).forEach { branch ->
            add(buildJsonObject { put("name", branch.name); put("sha", branch.sha) })
        } })
    }.toString()

    fun directory(owner: String, name: String, ref: String, path: String, entries: List<GitContent>) = buildJsonObject {
        put("type", "directory"); put("owner", owner); put("name", name); put("ref", ref); put("path", path)
        put("entries", buildJsonArray { entries.take(MAX_DIRECTORY_ENTRIES).forEach { entry ->
            add(buildJsonObject { put("path", entry.path); put("type", entry.type); put("size", entry.size); put("sha", entry.sha) })
        } }); put("truncated", entries.size > MAX_DIRECTORY_ENTRIES)
    }.toString()

    fun file(owner: String, name: String, ref: String, value: GitHubFileContent): String {
        require(value.size <= MAX_FILE_BYTES) { "GitHub 文件超过只读工具上限 $MAX_FILE_BYTES bytes" }
        return buildJsonObject {
            put("type", "file"); put("owner", owner); put("name", name); put("ref", ref)
            put("path", value.path); put("sha", value.sha); put("size", value.size); put("content", value.content)
        }.toString()
    }

    fun commits(page: Int, values: List<GitCommitSummary>) = buildJsonObject {
        put("page", page.coerceAtLeast(1)); put("commits", buildJsonArray { values.take(50).forEach { commit ->
            add(buildJsonObject { put("sha", commit.sha); put("message", commit.message)
                commit.author?.let { put("author", it) }; commit.committedAt?.let { put("committed_at", it) } })
        } })
    }.toString()

    fun workflowRuns(page: Int, values: List<WorkflowRunSummary>) = buildJsonObject {
        put("page", page.coerceAtLeast(1)); put("runs", buildJsonArray { values.take(20).forEach { run ->
            add(buildJsonObject { put("id", run.id); put("name", run.name); put("status", run.status)
                run.conclusion?.let { put("conclusion", it) }; put("head_sha", run.headSha) })
        } })
    }.toString()

    private fun repository(repository: GitHubRepositorySummary) = buildJsonObject {
        put("owner", repository.owner); put("name", repository.name)
        put("default_branch", repository.defaultBranch); put("private", repository.isPrivate)
        put("fork", repository.isFork); put("archived", repository.isArchived)
        repository.description?.let { put("description", it) }; repository.parent?.let { put("parent", it) }
        repository.pushedAt?.let { put("pushed_at", it) }
    }
}

/** Credentials remain inside the Android process and are never returned in tool output. */
class AndroidGitHubReadonlyAgentToolDataSource(context: Context) : GitHubReadonlyAgentToolDataSource {
    private val credentials = GitHubCredentialStore(context.applicationContext)

    override suspend fun listRepositories(page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return GitHubReadonlyToolRenderer.repositories(safePage, client().repositories(safePage))
    }

    override suspend fun getRepository(owner: String, name: String): String =
        GitHubReadonlyToolRenderer.repository(client().repository(owner, name))

    override suspend fun listBranches(owner: String, name: String): String =
        GitHubReadonlyToolRenderer.branches(client().branches(owner, name))

    override suspend fun readFile(owner: String, name: String, ref: String, path: String): String {
        val api = client()
        if (path.isEmpty()) return GitHubReadonlyToolRenderer.directory(owner, name, ref, path, api.directory(owner, name, ref, path))
        val entries = api.directory(owner, name, ref, path)
        if (entries.size != 1 || entries.first().path != path || entries.first().type == "dir") {
            return GitHubReadonlyToolRenderer.directory(owner, name, ref, path, entries)
        }
        return GitHubReadonlyToolRenderer.file(owner, name, ref, api.file(owner, name, ref, path))
    }

    override suspend fun listCommits(owner: String, name: String, ref: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return GitHubReadonlyToolRenderer.commits(safePage, client().commits(owner, name, ref, safePage))
    }

    override suspend fun getWorkflowRuns(owner: String, name: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        return GitHubReadonlyToolRenderer.workflowRuns(safePage, client().workflowRuns(owner, name, safePage))
    }

    private fun client(): GitHubApiClient {
        val token = credentials.loadToken()
        check(token.isNotEmpty()) { "GitHub 未登录，请先打开 GitHub 仓库入口完成登录" }
        return GitHubApiClient(token)
    }
}
