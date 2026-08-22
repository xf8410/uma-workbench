package com.uma.workbench.github

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/** Mutation adapter kept separate from the read-only GitHub client. */
class GitHubContributionGatewayImpl(
    private val token: String,
    private val apiBaseUrl: String = "https://api.github.com",
    private val policy: GitHubOperationPolicy = GitHubOperationPolicy()
) : GitHubContributionGateway {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fork(owner: String, repo: String, confirmationId: String?): GitHubForkResult {
        policy.requireConfirmation("创建 Fork $owner/$repo", confirmationId)
        val upstream = request("POST", "/repos/$owner/$repo/forks", "{}")
        val forkOwner = requiredObject(upstream, "owner").let { required(it, "login") }
        val forkRepo = required(upstream, "name")
        val upstreamInfo = request("GET", "/repos/$owner/$repo")
        val upstreamDefault = required(upstreamInfo, "default_branch")
        val forkDefault = upstream["default_branch"]?.jsonPrimitive?.contentOrNull ?: upstreamDefault
        return GitHubForkResult(
            GitHubForkBinding(owner, repo, forkOwner, forkRepo, upstreamDefault, forkDefault),
            upstream["html_url"]?.jsonPrimitive?.contentOrNull,
            true
        )
    }

    override suspend fun createWorkbenchBranch(binding: GitHubForkBinding, branch: String, confirmationId: String?): GitHubContributionBranch {
        policy.requireConfirmation("创建分支 ${binding.forkOwner}/${binding.forkRepo}:$branch", confirmationId)
        require(branch.startsWith("workbench/")) { "贡献分支必须使用 workbench/* 前缀" }
        val base = request("GET", "/repos/${binding.forkOwner}/${binding.forkRepo}/git/ref/heads/${branchName(binding.forkDefaultBranch)}")
        val sha = required(requiredObject(base, "object"), "sha")
        request("POST", "/repos/${binding.forkOwner}/${binding.forkRepo}/git/refs", encode(buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", sha)
        }))
        return GitHubContributionBranch(binding, branch, sha)
    }

    override suspend fun writeFile(binding: GitHubForkBinding, branch: String, change: GitHubFileChange, commitMessage: String, confirmationId: String?): GitRef {
        policy.requireConfirmation("写入 Fork 文件 ${binding.forkOwner}/${binding.forkRepo}:${change.path}", confirmationId)
        require(branch.startsWith("workbench/")) { "贡献分支必须使用 workbench/* 前缀" }
        val existing = runCatching {
            request("GET", "/repos/${binding.forkOwner}/${binding.forkRepo}/contents/${path(change.path)}?ref=${path(branch)}")
        }.getOrNull()
        val payload = buildJsonObject {
            put("message", commitMessage)
            put("content", Base64.getEncoder().encodeToString(change.content.toByteArray(Charsets.UTF_8)))
            put("branch", branch)
            existing?.get("sha")?.jsonPrimitive?.contentOrNull?.let { put("sha", it) }
        }
        val result = request("PUT", "/repos/${binding.forkOwner}/${binding.forkRepo}/contents/${path(change.path)}", encode(payload))
        return GitRef(branch, required(requiredObject(result, "commit"), "sha"))
    }

    override suspend fun createCrossForkPullRequest(req: GitHubCrossForkPullRequestRequest): GitHubContributionPullRequest {
        policy.requireConfirmation("向 ${req.binding.upstreamOwner}/${req.binding.upstreamRepo} 创建 Pull Request", req.confirmationId)
        GitHubContributionPolicy.validateTarget(req)
        val payload = buildJsonObject {
            put("title", req.title)
            put("body", req.body)
            put("head", GitHubContributionPolicy.headRef(req.binding, req.headBranch))
            put("base", req.baseBranch)
            put("draft", req.draft)
        }
        val result = request("POST", "/repos/${req.binding.upstreamOwner}/${req.binding.upstreamRepo}/pulls", encode(payload))
        val head = requiredObject(result, "head")
        val base = requiredObject(result, "base")
        val headRepo = requiredObject(head, "repo")
        val baseRepo = requiredObject(base, "repo")
        return GitHubContributionPullRequest(
            required(result, "number").toInt(),
            required(result, "html_url"),
            required(result, "state"),
            required(requiredObject(headRepo, "owner"), "login"),
            required(headRepo, "name"),
            required(head, "ref"),
            required(requiredObject(baseRepo, "owner"), "login"),
            required(baseRepo, "name"),
            required(base, "ref")
        )
    }

    private suspend fun request(method: String, path: String, body: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(apiBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = body != null
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) error("GitHub API $status: $text")
        json.parseToJsonElement(text).jsonObject
    }

    private fun encode(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)

    private fun required(value: JsonObject, name: String): String =
        value[name]?.jsonPrimitive?.contentOrNull ?: error("GitHub 响应缺少字段 $name")

    private fun requiredObject(value: JsonObject, name: String): JsonObject =
        value[name]?.jsonObject ?: error("GitHub 响应缺少对象字段 $name")

    private fun path(value: String): String = value.split('/').joinToString("/") {
        URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun branchName(value: String): String = path(value)
}
