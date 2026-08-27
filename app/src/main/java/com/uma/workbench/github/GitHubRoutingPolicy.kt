package com.uma.workbench.github

/** 项目赋予分支的用途；它与 GitHub 默认分支没有必然关系。 */
enum class GitHubBranchRole {
    STABLE_DATA,
    DEVELOPMENT,
    EXPERIMENT,
    TASK,
    RELEASE,
    UNKNOWN
}

/** 一条显式分支规则。pattern 支持精确名称以及末尾 `*` 前缀匹配。 */
data class GitHubBranchRule(
    val pattern: String,
    val role: GitHubBranchRole,
    val workflowsAllowed: Boolean,
    val preferredBase: String? = null
) {
    init {
        require(pattern.isNotBlank()) { "分支规则 pattern 不能为空" }
        require(!pattern.contains('*') || pattern.endsWith('*')) { "仅支持末尾 * 的分支前缀规则" }
    }

    fun matches(branch: String): Boolean = when {
        pattern.endsWith('*') -> branch.startsWith(pattern.dropLast(1))
        else -> branch == pattern
    }
}

/** 仓库级路由策略。没有匹配规则时必须返回 UNKNOWN，禁止回退到 default branch 猜测。 */
data class GitHubRepositoryRoutingPolicy(
    val owner: String,
    val repo: String,
    val branchRules: List<GitHubBranchRule>,
    val upstreamOwner: String? = null,
    val upstreamRepo: String? = null
) {
    init {
        require(owner.isNotBlank() && repo.isNotBlank()) { "仓库标识不能为空" }
        require(branchRules.isNotEmpty()) { "至少需要一条分支角色规则" }
    }

    fun ruleFor(branch: String): GitHubBranchRule? =
        branchRules.filter { it.matches(branch) }.maxByOrNull { it.pattern.removeSuffix("*").length }
}

/** PR 前必须展示并验证的四元组。 */
data class GitHubPullRequestRoute(
    val sourceOwner: String,
    val sourceRepo: String,
    val sourceBranch: String,
    val targetOwner: String,
    val targetRepo: String,
    val targetBranch: String,
    val ciOwner: String,
    val ciRepo: String,
    val ciRef: String,
    val canCancelCi: Boolean,
    val changedPaths: List<String> = emptyList(),
    val dispatchUpstreamWorkflow: Boolean = false
) {
    init {
        listOf(
            sourceOwner, sourceRepo, sourceBranch,
            targetOwner, targetRepo, targetBranch,
            ciOwner, ciRepo, ciRef
        ).forEach { require(it.isNotBlank()) { "PR/CI 路由四元组不能有空字段" } }
    }
}

enum class GitHubRouteBlockCode {
    SOURCE_ROLE_UNKNOWN,
    TARGET_ROLE_UNKNOWN,
    CI_ROLE_UNKNOWN,
    CI_NOT_IN_SOURCE_FORK,
    CI_CANNOT_CANCEL,
    UPSTREAM_WORKFLOW_DISPATCH,
    WORKFLOW_NOT_ALLOWED_ON_SOURCE,
    WORKFLOW_NOT_ALLOWED_ON_TARGET,
    SOURCE_BASE_ROLE_MISMATCH
}

data class GitHubRouteBlock(
    val code: GitHubRouteBlockCode,
    val message: String,
    val suggestedFix: String
)

data class GitHubRouteReview(
    val route: GitHubPullRequestRoute,
    val sourceRole: GitHubBranchRole,
    val targetRole: GitHubBranchRole,
    val ciRole: GitHubBranchRole,
    val blocks: List<GitHubRouteBlock>
) {
    val allowed: Boolean get() = blocks.isEmpty()

    fun requireAllowed() {
        require(allowed) {
            blocks.joinToString(prefix = "GitHub 路由被阻止：", separator = "；") { "${it.code}: ${it.message}" }
        }
    }
}

/**
 * 确定性的 PR/CI 路由闸门。
 *
 * 规则：
 * 1. 不从 main/master 或 GitHub default branch 推断用途；
 * 2. 预验证 CI 必须在来源 fork，且当前用户可取消；
 * 3. 不允许 Agora 主动 dispatch 上游工作流；
 * 4. workflow 文件只能出现在明确允许工作流的分支角色中。
 */
object GitHubRoutingGuard {
    fun review(
        route: GitHubPullRequestRoute,
        sourcePolicy: GitHubRepositoryRoutingPolicy,
        targetPolicy: GitHubRepositoryRoutingPolicy
    ): GitHubRouteReview {
        require(route.sourceOwner == sourcePolicy.owner && route.sourceRepo == sourcePolicy.repo) {
            "来源路由与来源仓库策略不一致"
        }
        require(route.targetOwner == targetPolicy.owner && route.targetRepo == targetPolicy.repo) {
            "目标路由与目标仓库策略不一致"
        }

        val sourceRule = sourcePolicy.ruleFor(route.sourceBranch)
        val targetRule = targetPolicy.ruleFor(route.targetBranch)
        val ciRule = if (route.ciOwner == sourcePolicy.owner && route.ciRepo == sourcePolicy.repo) {
            sourcePolicy.ruleFor(route.ciRef)
        } else null
        val hasWorkflowChanges = route.changedPaths.any { it.startsWith(".github/workflows/") }
        val blocks = buildList {
            if (sourceRule == null) add(block(
                GitHubRouteBlockCode.SOURCE_ROLE_UNKNOWN,
                "来源分支 ${route.sourceBranch} 没有显式角色",
                "先在项目策略中登记来源分支，不能按名称猜测"
            ))
            if (targetRule == null) add(block(
                GitHubRouteBlockCode.TARGET_ROLE_UNKNOWN,
                "目标分支 ${route.targetBranch} 没有显式角色",
                "先登记目标分支角色并重新预览 PR"
            ))
            if (ciRule == null) add(block(
                GitHubRouteBlockCode.CI_ROLE_UNKNOWN,
                "CI ref ${route.ciOwner}/${route.ciRepo}@${route.ciRef} 没有可验证角色",
                "把 CI 路由设为来源 fork 中已登记且含工作流的分支"
            ))
            if (route.ciOwner != route.sourceOwner || route.ciRepo != route.sourceRepo) add(block(
                GitHubRouteBlockCode.CI_NOT_IN_SOURCE_FORK,
                "CI 将运行在 ${route.ciOwner}/${route.ciRepo}，不是来源 fork",
                "改在 ${route.sourceOwner}/${route.sourceRepo} 的对应分支预验证"
            ))
            if (!route.canCancelCi) add(block(
                GitHubRouteBlockCode.CI_CANNOT_CANCEL,
                "当前用户不能取消目标 CI run",
                "选择自己有 Actions 管理权限的 fork 作为 CI 仓库"
            ))
            if (route.dispatchUpstreamWorkflow) add(block(
                GitHubRouteBlockCode.UPSTREAM_WORKFLOW_DISPATCH,
                "计划主动触发上游工作流",
                "禁止 dispatch 上游；只监控 fork CI，PR 后等待上游自身检查"
            ))
            if (hasWorkflowChanges && sourceRule?.workflowsAllowed == false) add(block(
                GitHubRouteBlockCode.WORKFLOW_NOT_ALLOWED_ON_SOURCE,
                "来源分支角色 ${sourceRule.role} 不允许承载工作流",
                "把实验工作流留在允许工作流的开发/实验分支"
            ))
            if (hasWorkflowChanges && targetRule?.workflowsAllowed == false) add(block(
                GitHubRouteBlockCode.WORKFLOW_NOT_ALLOWED_ON_TARGET,
                "PR 会把 .github/workflows/** 带入不接收工作流的目标分支",
                "从上游 PR 分支剔除工作流，只提交最终代码和数据"
            ))
            sourceRule?.preferredBase?.let { expected ->
                if (expected != route.targetBranch) add(block(
                    GitHubRouteBlockCode.SOURCE_BASE_ROLE_MISMATCH,
                    "来源分支配置的目标基线是 $expected，当前目标是 ${route.targetBranch}",
                    "选择正确目标分支，或显式修改项目分支策略后重新审查"
                ))
            }
        }
        return GitHubRouteReview(
            route = route,
            sourceRole = sourceRule?.role ?: GitHubBranchRole.UNKNOWN,
            targetRole = targetRule?.role ?: GitHubBranchRole.UNKNOWN,
            ciRole = ciRule?.role ?: GitHubBranchRole.UNKNOWN,
            blocks = blocks
        )
    }

    private fun block(code: GitHubRouteBlockCode, message: String, fix: String) =
        GitHubRouteBlock(code, message, fix)
}
