package com.uma.workbench.github

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.content

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
        val forkOwner = upstream["owner"]!!.jsonObject["login"]!!.jsonPrimitive.content
        val forkRepo = upstream["name"]!!.jsonPrimitive.content
        val upstreamInfo = request("GET", "/repos/$owner/$repo")
        val upstreamDefault = upstreamInfo["default_branch"]!!.jsonPrimitive.content
        val forkDefault = upstream["default_branch"]?.jsonPrimitive?.content ?: upstreamDefault
        return GitHubForkResult(GitHubForkBinding(owner, repo, forkOwner, forkRepo, upstreamDefault, forkDefault), upstream["html_url"]?.jsonPrimitive?.content, true)
    }

    override suspend fun createWorkbenchBranch(binding: GitHubForkBinding, branch: String, confirmationId: String?): GitHubContributionBranch {
        policy.requireConfirmation("创建分支 ${binding.forkOwner}/${binding.forkRepo}:$branch", confirmationId)
        require(branch.startsWith("workbench/"))
        val base = request("GET", "/repos/${binding.forkOwner}/${binding.forkRepo}/git/ref/heads/${branchName(binding.forkDefaultBranch)}")
        val sha = base["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
        request("POST", "/repos/${binding.forkOwner}/${binding.forkRepo}/git/refs", json.encodeToString(buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", sha) }))
        return GitHubContributionBranch(binding, branch, sha)
    }

    override suspend fun writeFile(binding: GitHubForkBinding, branch: String, change: GitHubFileChange, commitMessage: String, confirmationId: String?): GitRef {
        policy.requireConfirmation("写入 Fork 文件 ${binding.forkOwner}/${binding.forkRepo}:${change.path}", confirmationId)
        require(branch.startsWith("workbench/"))
        val existing = runCatching { request("GET", "/repos/${binding.forkOwner}/${binding.forkRepo}/contents/${path(change.path)}?ref=$branch") }.getOrNull()
        val payload = buildJsonObject { put("message", commitMessage); put("content", Base64.getEncoder().encodeToString(change.content.toByteArray(Charsets.UTF_8))); put("branch", branch); existing?.get("sha")?.jsonPrimitive?.content?.let { put("sha", it) } }
        val result = request("PUT", "/repos/${binding.forkOwner}/${binding.forkRepo}/contents/${path(change.path)}", json.encodeToString(payload))
        return GitRef(branch, result["commit"]!!.jsonObject["sha"]!!.jsonPrimitive.content)
    }

    override suspend fun createCrossForkPullRequest(req: GitHubCrossForkPullRequestRequest): GitHubContributionPullRequest {
        policy.requireConfirmation("向 ${req.binding.upstreamOwner}/${req.binding.upstreamRepo} 创建 Pull Request", req.confirmationId)
        GitHubContributionPolicy.validateTarget(req)
        val payload = buildJsonObject { put("title", req.title); put("body", req.body); put("head", GitHubContributionPolicy.headRef(req.binding, req.headBranch)); put("base", req.baseBranch); put("draft", req.draft) }
        val result = request("POST", "/repos/${req.binding.upstreamOwner}/${req.binding.upstreamRepo}/pulls", json.encodeToString(payload))
        val head = result["head"]!!.jsonObject; val base = result["base"]!!.jsonObject
        return GitHubContributionPullRequest(result["number"]!!.jsonPrimitive.int, result["html_url"]!!.jsonPrimitive.content, result["state"]!!.jsonPrimitive.content, head["repo"]!!.jsonObject["owner"]!!.jsonPrimitive.content, head["repo"]!!.jsonObject["name"]!!.jsonPrimitive.content, head["ref"]!!.jsonPrimitive.content, base["repo"]!!.jsonObject["owner"]!!.jsonPrimitive.content, base["repo"]!!.jsonPrimitive.content, base["ref"]!!.jsonPrimitive.content)
    }

    private suspend fun request(method: String, path: String, body: String? = null): kotlinx.serialization.json.JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(apiBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000; doOutput = body != null; setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("X-GitHub-Api-Version", "2022-11-28"); setRequestProperty("Authorization", "Bearer $token"); if (body != null) { setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(body.toByteArray()) } } }
        val status = connection.responseCode; val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty(); if (status !in 200..299) error("GitHub API $status: $text"); json.parseToJsonElement(text).jsonObject
    }
    private fun path(value: String) = value.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun branchName(value: String) = path(value)
}
