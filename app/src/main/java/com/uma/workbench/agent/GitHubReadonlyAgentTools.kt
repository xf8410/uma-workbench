package com.uma.workbench.agent

import android.content.Context
import com.uma.workbench.github.GitHubApiClient
import com.uma.workbench.github.GitHubCredentialStore
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

/** Credentials remain inside the Android process and are never returned in tool output. */
class AndroidGitHubReadonlyAgentToolDataSource(context: Context) : GitHubReadonlyAgentToolDataSource {
    private val credentials = GitHubCredentialStore(context.applicationContext)

    override suspend fun listRepositories(page: Int): String {
        val repositories = client().repositories(page.coerceAtLeast(1))
        return buildJsonObject {
            put("page", page.coerceAtLeast(1))
            put("repositories", buildJsonArray {
                repositories.forEach { repository -> add(buildJsonObject {
                    put("owner", repository.owner); put("name", repository.name)
                    put("default_branch", repository.defaultBranch); put("private", repository.isPrivate)
                    put("fork", repository.isFork); put("archived", repository.isArchived)
                    repository.description?.let { put("description", it) }
                    repository.pushedAt?.let { put("pushed_at", it) }
                }) }
            })
        }.toString()
    }

    override suspend fun getRepository(owner: String, name: String): String {
        val repository = client().repository(owner, name)
        return buildJsonObject {
            put("owner", repository.owner); put("name", repository.name)
            put("default_branch", repository.defaultBranch); put("private", repository.isPrivate)
            put("fork", repository.isFork); put("archived", repository.isArchived)
            repository.description?.let { put("description", it) }
            repository.parent?.let { put("parent", it) }
            repository.pushedAt?.let { put("pushed_at", it) }
        }.toString()
    }

    override suspend fun listBranches(owner: String, name: String): String = buildJsonObject {
        put("branches", buildJsonArray { client().branches(owner, name).take(100).forEach { branch ->
            add(buildJsonObject { put("name", branch.name); put("sha", branch.sha) })
        } })
    }.toString()

    override suspend fun readFile(owner: String, name: String, ref: String, path: String): String {
        val api = client()
        if (path.isEmpty()) return directoryJson(api, owner, name, ref, path)
        val entries = api.directory(owner, name, ref, path)
        if (entries.size != 1 || entries.first().path != path || entries.first().type == "dir") {
            return directoryJson(entries, owner, name, ref, path)
        }
        val file = api.file(owner, name, ref, path)
        require(file.size <= MAX_FILE_BYTES) { "GitHub 文件超过只读工具上限 $MAX_FILE_BYTES bytes" }
        return buildJsonObject {
            put("type", "file"); put("owner", owner); put("name", name); put("ref", ref)
            put("path", file.path); put("sha", file.sha); put("size", file.size); put("content", file.content)
        }.toString()
    }

    override suspend fun listCommits(owner: String, name: String, ref: String, page: Int): String = buildJsonObject {
        put("page", page.coerceAtLeast(1)); put("commits", buildJsonArray {
            client().commits(owner, name, ref, page.coerceAtLeast(1)).take(50).forEach { commit ->
                add(buildJsonObject { put("sha", commit.sha); put("message", commit.message)
                    commit.author?.let { put("author", it) }; commit.committedAt?.let { put("committed_at", it) } })
            }
        })
    }.toString()

    override suspend fun getWorkflowRuns(owner: String, name: String, page: Int): String = buildJsonObject {
        put("page", page.coerceAtLeast(1)); put("runs", buildJsonArray {
            client().workflowRuns(owner, name, page.coerceAtLeast(1)).take(20).forEach { run ->
                add(buildJsonObject { put("id", run.id); put("name", run.name); put("status", run.status)
                    run.conclusion?.let { put("conclusion", it) }; put("head_sha", run.headSha) })
            }
        })
    }.toString()

    private suspend fun directoryJson(api: GitHubApiClient, owner: String, name: String, ref: String, path: String) =
        directoryJson(api.directory(owner, name, ref, path), owner, name, ref, path)

    private fun directoryJson(entries: List<com.uma.workbench.github.GitContent>, owner: String, name: String, ref: String, path: String) =
        buildJsonObject {
            put("type", "directory"); put("owner", owner); put("name", name); put("ref", ref); put("path", path)
            put("entries", buildJsonArray { entries.take(MAX_DIRECTORY_ENTRIES).forEach { entry ->
                add(buildJsonObject { put("path", entry.path); put("type", entry.type); put("size", entry.size); put("sha", entry.sha) })
            } }); put("truncated", entries.size > MAX_DIRECTORY_ENTRIES)
        }.toString()

    private fun client(): GitHubApiClient {
        val token = credentials.loadToken()
        check(token.isNotEmpty()) { "GitHub 未登录，请先打开 GitHub 仓库入口完成登录" }
        return GitHubApiClient(token)
    }

    private companion object {
        const val MAX_FILE_BYTES = 750_000L
        const val MAX_DIRECTORY_ENTRIES = 500
    }
}
