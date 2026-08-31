package com.uma.workbench.github

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Authenticated GitHub account returned by GET /user. */
data class GitHubAccount(
    val login: String,
    val id: Long,
    val avatarUrl: String?,
    val name: String?
)

data class GitHubFileContent(
    val path: String,
    val sha: String,
    val size: Long,
    val content: String,
    val encoding: String
)

class GitHubApiException(
    val statusCode: Int,
    val responseBody: String
) : IllegalStateException("GitHub API $statusCode: $responseBody")

/** Minimal GitHub REST client. Read calls never mutate the remote repository. */
class GitHubApiClient(
    private val token: String,
    private val apiBaseUrl: String = "https://api.github.com"
) : GitHubGateway {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun account(): GitHubAccount {
        val value = runCatching { get("/user").jsonObject }.getOrElse {
            // fine-grained PAT 未授予账号读取权限时 /user 返回 404/403；
            // 用仓库列表的 owner 兜底识别登录名，令牌本身仍可用于仓库操作。
            val repos = get("/user/repos?per_page=1&sort=updated").jsonArray
            val owner = repos.firstOrNull()?.jsonObject?.get("owner")?.jsonObject
            return GitHubAccount(
                login = owner?.string("login") ?: "token-user",
                id = owner?.long("id") ?: 0L,
                avatarUrl = owner?.optionalString("avatar_url"),
                name = "令牌用户（/user 不可读，通常为 fine-grained 令牌未授予账号权限）"
            )
        }
        return GitHubAccount(
            login = value.string("login"),
            id = value.long("id"),
            avatarUrl = value.optionalString("avatar_url"),
            name = value.optionalString("name")
        )
    }

    override suspend fun repositories(page: Int): List<GitHubRepositorySummary> =
        get("/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member")
            .jsonArray.map(::repositorySummary)

    override suspend fun repository(owner: String, name: String): GitHubRepositorySummary =
        repositorySummary(get("/repos/${segment(owner)}/${segment(name)}"))

    override suspend fun branches(owner: String, name: String): List<GitRef> =
        get("/repos/${segment(owner)}/${segment(name)}/branches?per_page=100")
            .jsonArray.map { element ->
                val value = element.jsonObject
                GitRef(value.string("name"), value.objectValue("commit").string("sha"))
            }

    override suspend fun tags(owner: String, name: String): List<GitRef> =
        get("/repos/${segment(owner)}/${segment(name)}/tags?per_page=100")
            .jsonArray.map { element ->
                val value = element.jsonObject
                GitRef(value.string("name"), value.objectValue("commit").string("sha"))
            }

    override suspend fun commits(owner: String, name: String, ref: String, page: Int): List<GitCommitSummary> =
        get("/repos/${segment(owner)}/${segment(name)}/commits?sha=${query(ref)}&per_page=100&page=$page")
            .jsonArray.map { element ->
                val value = element.jsonObject
                val commit = value.objectValue("commit")
                val author = commit["author"] as? JsonObject
                GitCommitSummary(
                    sha = value.string("sha"),
                    message = commit.string("message"),
                    author = author?.optionalString("name"),
                    committedAt = author?.optionalString("date")
                )
            }

    override suspend fun directory(owner: String, name: String, ref: String, path: String): List<GitContent> {
        val suffix = if (path.isEmpty()) "" else "/${path.split('/').joinToString("/") { segment(it) }}"
        val value = get("/repos/${segment(owner)}/${segment(name)}/contents$suffix?ref=${query(ref)}")
        return when (value) {
            is JsonArray -> value.map(::contentSummary)
            is JsonObject -> listOf(contentSummary(value))
            else -> emptyList()
        }
    }

    suspend fun file(owner: String, name: String, ref: String, path: String): GitHubFileContent {
        val encodedPath = path.split('/').joinToString("/") { segment(it) }
        val value = get("/repos/${segment(owner)}/${segment(name)}/contents/$encodedPath?ref=${query(ref)}").jsonObject
        val encoding = value.string("encoding")
        val encoded = value.string("content").filterNot(Char::isWhitespace)
        val content = when (encoding.lowercase()) {
            "base64" -> String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            else -> encoded
        }
        return GitHubFileContent(value.string("path"), value.string("sha"), value.long("size"), content, encoding)
    }

    override suspend fun issues(owner: String, name: String, page: Int): List<GitHubIssueSummary> =
        get("/repos/${segment(owner)}/${segment(name)}/issues?state=all&per_page=100&page=$page")
            .jsonArray.filter { it.jsonObject["pull_request"] == null }.map { element ->
                val value = element.jsonObject
                GitHubIssueSummary(value.long("number").toInt(), value.string("title"), value.string("state"), value.optionalString("updated_at"))
            }

    override suspend fun pullRequests(owner: String, name: String, page: Int): List<GitPullRequestSummary> =
        get("/repos/${segment(owner)}/${segment(name)}/pulls?state=all&per_page=100&page=$page")
            .jsonArray.map { element ->
                val value = element.jsonObject
                GitPullRequestSummary(
                    value.long("number").toInt(),
                    value.string("title"),
                    value.string("state"),
                    value["draft"]?.jsonPrimitive?.booleanOrNull == true,
                    value["merged_at"] != null && value["merged_at"].toString() != "null"
                )
            }

    override suspend fun workflowRuns(owner: String, name: String, page: Int): List<WorkflowRunSummary> =
        get("/repos/${segment(owner)}/${segment(name)}/actions/runs?per_page=100&page=$page")
            .jsonObject.array("workflow_runs").map { element ->
                val value = element.jsonObject
                WorkflowRunSummary(value.long("id"), value.string("name"), value.string("status"), value.optionalString("conclusion"), value.string("head_sha"))
            }

    override suspend fun artifacts(owner: String, name: String, runId: Long): List<ArtifactSummary> =
        get("/repos/${segment(owner)}/${segment(name)}/actions/runs/$runId/artifacts?per_page=100")
            .jsonObject.array("artifacts").map { element ->
                val value = element.jsonObject
                ArtifactSummary(value.long("id"), value.string("name"), value.long("size_in_bytes"), value["expired"]?.jsonPrimitive?.booleanOrNull == true)
            }

    private suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        val connection = (URL(apiBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw GitHubApiException(status, body)
        json.parseToJsonElement(body)
    }

    private fun repositorySummary(element: JsonElement): GitHubRepositorySummary {
        val value = element.jsonObject
        val owner = value.objectValue("owner").string("login")
        return GitHubRepositorySummary(
            id = value.long("id"),
            owner = owner,
            name = value.string("name"),
            description = value.optionalString("description"),
            defaultBranch = value.string("default_branch"),
            isPrivate = value["private"]?.jsonPrimitive?.booleanOrNull == true,
            isFork = value["fork"]?.jsonPrimitive?.booleanOrNull == true,
            isArchived = value["archived"]?.jsonPrimitive?.booleanOrNull == true,
            parent = (value["parent"] as? JsonObject)?.optionalString("full_name"),
            pushedAt = value.optionalString("pushed_at")
        )
    }

    private fun contentSummary(element: JsonElement): GitContent {
        val value = element.jsonObject
        return GitContent(value.string("path"), value.string("type"), value.long("size"), value.string("sha"))
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull ?: error("GitHub 响应缺少字段 $name")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: error("GitHub 响应缺少数字字段 $name")

    private fun JsonObject.objectValue(name: String): JsonObject =
        get(name) as? JsonObject ?: error("GitHub 响应缺少对象字段 $name")

    private fun JsonObject.array(name: String): JsonArray =
        get(name) as? JsonArray ?: error("GitHub 响应缺少数组字段 $name")

    private fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun query(value: String): String = segment(value)
}
