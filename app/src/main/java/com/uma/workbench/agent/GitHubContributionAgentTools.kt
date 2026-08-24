package com.uma.workbench.agent

import android.content.Context
import com.uma.workbench.github.GitHubContributionBranch
import com.uma.workbench.github.GitHubConfirmationStore
import com.uma.workbench.github.GitHubContributionGatewayImpl
import com.uma.workbench.github.GitHubOperationPolicy
import com.uma.workbench.github.GitHubContributionPullRequest
import com.uma.workbench.github.GitHubContributionProgress
import com.uma.workbench.github.GitHubContributionWorkflow
import com.uma.workbench.github.GitHubCredentialStore
import com.uma.workbench.github.GitHubFileChange
import com.uma.workbench.github.GitHubForkBinding
import com.uma.workbench.github.GitHubForkResult
import com.uma.workbench.github.GitRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent 侧的 GitHub 贡献流数据源：fork → workbench 分支 → 文件提交 → 跨 fork PR。
 *
 * 每一步都要 confirmationId（远程写操作的显式确认令牌，由 UI 确认流程发放）。
 * 进度快照以 JSON 字符串在工具调用间传递——Agent 把上一步返回的 progress
 * 原样传给下一步，无服务端状态，可审计、可重放。
 */
interface GitHubContributionAgentToolDataSource {
    suspend fun forkRepository(owner: String, repo: String, confirmationId: String): String
    suspend fun createBranch(progress: String, branch: String, confirmationId: String): String
    suspend fun writeFile(progress: String, path: String, content: String, commitMessage: String, confirmationId: String): String
    suspend fun createPullRequest(progress: String, title: String, body: String, draft: Boolean, confirmationId: String): String
}

internal object GitHubContributionProgressCodec {
    fun encode(progress: GitHubContributionProgress): String {
        val root = buildJsonObject {
            put("upstreamOwner", progress.upstreamOwner)
            put("upstreamRepo", progress.upstreamRepo)
            progress.fork?.let { put("fork", forkToJson(it)) }
            progress.branch?.let { put("branch", branchToJson(it)) }
            if (progress.commits.isNotEmpty()) {
                put("commits", buildJsonObject {
                    progress.commits.forEachIndexed { index, ref ->
                        put("$index", buildJsonObject {
                            put("name", ref.name); put("sha", ref.sha)
                        })
                    }
                })
            }
            progress.pullRequest?.let { put("pullRequest", pullRequestToJson(it)) }
            progress.error?.let { put("error", it) }
        }
        return root.toString()
    }

    fun decode(json: String): GitHubContributionProgress {
        val root = Json.parseToJsonElement(json).jsonObject
        val upstreamOwner = root.string("upstreamOwner")
        val upstreamRepo = root.string("upstreamRepo")
        require(upstreamOwner.isNotBlank() && upstreamRepo.isNotBlank()) { "progress JSON 缺少上游仓库标识" }
        val fork = (root["fork"] as? JsonObject)?.let { forkFromJson(it) }
        val branch = (root["branch"] as? JsonObject)?.let { branchFromJson(it) }
        val commits = (root["commits"] as? JsonObject)?.let { commitsObj ->
            (0 until commitsObj.size).mapNotNull { index ->
                (commitsObj["$index"] as? JsonObject)?.let {
                    GitRef(it.string("name"), it.string("sha"))
                }
            }
        } ?: emptyList()
        val pullRequest = (root["pullRequest"] as? JsonObject)?.let { pullRequestFromJson(it) }
        return GitHubContributionProgress(
            upstreamOwner = upstreamOwner,
            upstreamRepo = upstreamRepo,
            fork = fork,
            branch = branch,
            commits = commits,
            pullRequest = pullRequest,
            error = (root["error"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        )
    }

    private fun forkToJson(fork: GitHubForkResult): JsonObject = buildJsonObject {
        put("binding", bindingToJson(fork.binding))
        fork.htmlUrl?.let { put("htmlUrl", it) }
        put("ready", fork.ready)
    }

    private fun forkFromJson(json: JsonObject): GitHubForkResult = GitHubForkResult(
        binding = bindingFromJson(json["binding"]?.jsonObject ?: error("fork 缺少 binding")),
        htmlUrl = json.stringOrNull("htmlUrl"),
        ready = json["ready"]?.jsonPrimitive?.contentOrNull == "true"
    )

    private fun branchToJson(branch: GitHubContributionBranch): JsonObject = buildJsonObject {
        put("binding", bindingToJson(branch.binding))
        put("branch", branch.branch)
        put("headSha", branch.headSha)
    }

    private fun branchFromJson(json: JsonObject): GitHubContributionBranch = GitHubContributionBranch(
        binding = bindingFromJson(json["binding"]?.jsonObject ?: error("branch 缺少 binding")),
        branch = json.string("branch"),
        headSha = json.string("headSha")
    )

    private fun pullRequestToJson(pr: GitHubContributionPullRequest): JsonObject = buildJsonObject {
        put("number", pr.number)
        put("htmlUrl", pr.htmlUrl)
        put("state", pr.state)
        put("headOwner", pr.headOwner)
        put("headRepo", pr.headRepo)
        put("headBranch", pr.headBranch)
        put("baseOwner", pr.baseOwner)
        put("baseRepo", pr.baseRepo)
        put("baseBranch", pr.baseBranch)
    }

    private fun pullRequestFromJson(json: JsonObject): GitHubContributionPullRequest = GitHubContributionPullRequest(
        number = json.string("number").toInt(),
        htmlUrl = json.string("htmlUrl"),
        state = json.string("state"),
        headOwner = json.string("headOwner"),
        headRepo = json.string("headRepo"),
        headBranch = json.string("headBranch"),
        baseOwner = json.string("baseOwner"),
        baseRepo = json.string("baseRepo"),
        baseBranch = json.string("baseBranch")
    )

    private fun bindingToJson(binding: GitHubForkBinding): JsonObject = buildJsonObject {
        put("upstreamOwner", binding.upstreamOwner)
        put("upstreamRepo", binding.upstreamRepo)
        put("forkOwner", binding.forkOwner)
        put("forkRepo", binding.forkRepo)
        put("upstreamDefaultBranch", binding.upstreamDefaultBranch)
        put("forkDefaultBranch", binding.forkDefaultBranch)
    }

    private fun bindingFromJson(json: JsonObject): GitHubForkBinding = GitHubForkBinding(
        upstreamOwner = json.string("upstreamOwner"),
        upstreamRepo = json.string("upstreamRepo"),
        forkOwner = json.string("forkOwner"),
        forkRepo = json.string("forkRepo"),
        upstreamDefaultBranch = json.string("upstreamDefaultBranch"),
        forkDefaultBranch = json.string("forkDefaultBranch")
    )

    private fun JsonObject.string(name: String): String =
        stringOrNull(name) ?: error("progress JSON 缺少字段 $name")

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

internal object GitHubContributionToolRenderer {
    fun fork(result: GitHubForkResult, progress: String): String = buildString {
        appendLine("## Fork 完成")
        appendLine()
        appendLine("- 上游：${result.binding.upstreamOwner}/${result.binding.upstreamRepo}")
        appendLine("- Fork：${result.binding.forkOwner}/${result.binding.forkRepo}（默认分支 ${result.binding.forkDefaultBranch}）")
        result.htmlUrl?.let { appendLine("- 地址：$it") }
        appendLine("- 就绪：${if (result.ready) "是" else "否（GitHub 异步创建中，稍后重试）"}")
        appendLine()
        appendLine("### progress（传给下一步工具的 progress 参数）")
        appendLine()
        appendLine("```json")
        appendLine(progress)
        appendLine("```")
    }

    fun branch(branch: GitHubContributionBranch, progress: String): String = buildString {
        appendLine("## 分支创建完成")
        appendLine()
        appendLine("- 分支：${branch.branch}（fork ${branch.binding.forkOwner}/${branch.binding.forkRepo}）")
        appendLine("- 基于上游分支：${branch.binding.upstreamDefaultBranch}")
        appendLine()
        appendLine("### progress（传给下一步工具的 progress 参数）")
        appendLine()
        appendLine("```json")
        appendLine(progress)
        appendLine("```")
    }

    fun commit(ref: GitRef, path: String, progress: String, commitCount: Int): String = buildString {
        appendLine("## 文件提交完成")
        appendLine()
        appendLine("- 文件：`$path`")
        appendLine("- 提交序列：第 $commitCount 个")
        appendLine()
        appendLine("### progress（传给下一步工具的 progress 参数）")
        appendLine()
        appendLine("```json")
        appendLine(progress)
        appendLine("```")
    }

    fun pullRequest(pr: GitHubContributionPullRequest, progress: String): String = buildString {
        appendLine("## 跨 fork PR 创建完成")
        appendLine()
        appendLine("- PR #${pr.number}：${pr.htmlUrl}")
        appendLine("- ${pr.headOwner}:${pr.headBranch} → ${pr.baseOwner}/${pr.baseRepo}@${pr.baseBranch}")
        appendLine("- 状态：${pr.state}")
        appendLine()
        appendLine("### progress（最终快照）")
        appendLine()
        appendLine("```json")
        appendLine(progress)
        appendLine("```")
    }
}

/** 生产实现：token 存取与 gateway 组装。 */
class AndroidGitHubContributionAgentToolDataSource(
    context: Context,
    private val confirmationStore: GitHubConfirmationStore? = null
) : GitHubContributionAgentToolDataSource {

    private val credentials = GitHubCredentialStore(context)

    private fun workflow(): GitHubContributionWorkflow {
        val token = credentials.loadToken()
        check(token.isNotEmpty()) { "GitHub 未登录，请先打开 GitHub 仓库入口完成登录" }
        return GitHubContributionWorkflow(
            GitHubContributionGatewayImpl(
                token,
                policy = GitHubOperationPolicy(confirmationStore)
            )
        )
    }

    override suspend fun forkRepository(owner: String, repo: String, confirmationId: String): String {
        val progress = workflow().createFork(owner, repo, confirmationId)
        return GitHubContributionToolRenderer.fork(
            checkNotNull(progress.fork) { "fork 未返回结果" },
            GitHubContributionProgressCodec.encode(progress)
        )
    }

    override suspend fun createBranch(progress: String, branch: String, confirmationId: String): String {
        val result = workflow().createBranch(GitHubContributionProgressCodec.decode(progress), branch, confirmationId)
        return GitHubContributionToolRenderer.branch(
            checkNotNull(result.branch) { "分支未返回结果" },
            GitHubContributionProgressCodec.encode(result)
        )
    }

    override suspend fun writeFile(progress: String, path: String, content: String, commitMessage: String, confirmationId: String): String {
        val decoded = GitHubContributionProgressCodec.decode(progress)
        val result = workflow().commitFiles(
            decoded,
            listOf(GitHubFileChange(path, content)),
            commitMessage,
            confirmationId
        )
        val last = result.commits.lastOrNull() ?: error("提交未返回引用")
        return GitHubContributionToolRenderer.commit(
            last, path, GitHubContributionProgressCodec.encode(result), result.commits.size
        )
    }

    override suspend fun createPullRequest(progress: String, title: String, body: String, draft: Boolean, confirmationId: String): String {
        val result = workflow().createPullRequest(
            GitHubContributionProgressCodec.decode(progress), title, body, draft, confirmationId
        )
        return GitHubContributionToolRenderer.pullRequest(
            checkNotNull(result.pullRequest) { "PR 未返回结果" },
            GitHubContributionProgressCodec.encode(result)
        )
    }
}
