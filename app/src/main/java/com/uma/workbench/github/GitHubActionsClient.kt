package com.uma.workbench.github

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Agora 内显示的 Actions run，保留仓库、ref 与 URL，避免把上游 run 误当成自己的 run。 */
data class GitHubActionsRun(
    val id: Long,
    val repository: String,
    val name: String,
    val event: String,
    val status: String,
    val conclusion: String?,
    val headBranch: String,
    val headSha: String,
    val runAttempt: Int,
    val htmlUrl: String,
    val createdAt: String?,
    val updatedAt: String?
) {
    val active: Boolean get() = status == "queued" || status == "in_progress" || status == "pending" || status == "waiting"
}

data class GitHubActionsJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String?,
    val steps: List<GitHubActionsStep>
)

data class GitHubActionsStep(
    val number: Int,
    val name: String,
    val status: String,
    val conclusion: String?
)

data class GitHubActionsRunDetails(
    val run: GitHubActionsRun,
    val jobs: List<GitHubActionsJob>
)

/** 取消前由 UI 展示的精确目标。 */
data class GitHubActionsCancellationTarget(
    val owner: String,
    val repo: String,
    val runId: Long,
    val expectedHeadSha: String,
    val runName: String
) {
    init {
        require(owner.isNotBlank() && repo.isNotBlank())
        require(runId > 0)
        require(expectedHeadSha.length >= 7) { "取消 CI 前必须绑定预期 Head SHA" }
        require(runName.isNotBlank())
    }
}

/** Actions 读写客户端。取消是远程变更，必须持有 UI 发放的 confirmationId。 */
class GitHubActionsClient(
    private val token: String,
    private val policy: GitHubOperationPolicy,
    private val apiBaseUrl: String = "https://api.github.com"
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun runs(owner: String, repo: String, branch: String? = null, page: Int = 1): List<GitHubActionsRun> {
        require(page > 0)
        val branchQuery = branch?.takeIf { it.isNotBlank() }?.let { "&branch=${query(it)}" }.orEmpty()
        val root = request(
            "GET",
            "/repos/${segment(owner)}/${segment(repo)}/actions/runs?per_page=30&page=$page$branchQuery"
        ).jsonObject
        return root.array("workflow_runs").map { parseRun("$owner/$repo", it.jsonObject) }
    }

    suspend fun details(owner: String, repo: String, runId: Long): GitHubActionsRunDetails {
        require(runId > 0)
        val repository = "$owner/$repo"
        val run = parseRun(repository, request("GET", "/repos/${segment(owner)}/${segment(repo)}/actions/runs/$runId").jsonObject)
        val jobsRoot = request("GET", "/repos/${segment(owner)}/${segment(repo)}/actions/runs/$runId/jobs?per_page=100").jsonObject
        val jobs = jobsRoot.array("jobs").map { value ->
            val job = value.jsonObject
            GitHubActionsJob(
                id = job.long("id"),
                name = job.string("name"),
                status = job.string("status"),
                conclusion = job.optionalString("conclusion"),
                htmlUrl = job.optionalString("html_url"),
                steps = (job["steps"] as? JsonArray).orEmpty().map { stepElement ->
                    val step = stepElement.jsonObject
                    GitHubActionsStep(
                        number = step.long("number").toInt(),
                        name = step.string("name"),
                        status = step.string("status"),
                        conclusion = step.optionalString("conclusion")
                    )
                }
            )
        }
        return GitHubActionsRunDetails(run, jobs)
    }

    suspend fun cancel(target: GitHubActionsCancellationTarget, confirmationId: String) {
        policy.requireConfirmation(
            GitHubRemoteOperation.CANCEL_WORKFLOW,
            "取消 ${target.owner}/${target.repo} run ${target.runId} (${target.runName})",
            confirmationId
        )
        val current = details(target.owner, target.repo, target.runId).run
        require(current.headSha == target.expectedHeadSha) {
            "Run Head SHA 已变化，拒绝取消：预期 ${target.expectedHeadSha}，实际 ${current.headSha}"
        }
        require(current.active) { "Run ${target.runId} 已不是可取消状态：${current.status}/${current.conclusion}" }
        request(
            "POST",
            "/repos/${segment(target.owner)}/${segment(target.repo)}/actions/runs/${target.runId}/cancel",
            body = ""
        )
    }

    private suspend fun request(method: String, path: String, body: String? = null): JsonElement = withContext(Dispatchers.IO) {
        val connection = (URL(apiBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = body != null
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw GitHubApiException(status, text)
        if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
    }

    private fun parseRun(repository: String, value: JsonObject): GitHubActionsRun = GitHubActionsRun(
        id = value.long("id"),
        repository = repository,
        name = value.string("name"),
        event = value.string("event"),
        status = value.string("status"),
        conclusion = value.optionalString("conclusion"),
        headBranch = value.optionalString("head_branch").orEmpty(),
        headSha = value.string("head_sha"),
        runAttempt = value.long("run_attempt").toInt(),
        htmlUrl = value.string("html_url"),
        createdAt = value.optionalString("created_at"),
        updatedAt = value.optionalString("updated_at")
    )

    private fun JsonObject.string(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull ?: error("GitHub Actions 响应缺少字段 $name")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: error("GitHub Actions 响应缺少数字字段 $name")

    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.jsonArray ?: error("GitHub Actions 响应缺少数组字段 $name")

    private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun query(value: String): String = segment(value)
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
