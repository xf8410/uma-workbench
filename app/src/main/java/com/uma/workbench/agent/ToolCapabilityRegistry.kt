package com.uma.workbench.agent

/**
 * Risk level classification for agent tools.
 * Higher levels require stricter approval flows.
 */
enum class ToolRiskLevel {
    READ_ONLY,
    LOCAL_WRITE,
    REMOTE_WRITE,
    DESTRUCTIVE
}

/**
 * Describes a tool's capabilities and safety metadata.
 * Used by the approval gate and execution planner.
 */
data class ToolCapability(
    val name: String,
    val description: String,
    val riskLevel: ToolRiskLevel,
    val requiresApproval: Boolean = riskLevel >= ToolRiskLevel.LOCAL_WRITE,
    val maxCallsPerRun: Int? = null
)

/**
 * Registry of tool capabilities with risk levels.
 * Determines which tools need user confirmation before execution.
 */
class ToolCapabilityRegistry {
    private val capabilities = linkedMapOf<String, ToolCapability>()

    fun register(capability: ToolCapability) {
        require(capability.name.isNotBlank()) { "工具名称不能为空" }
        capabilities[capability.name] = capability
    }

    fun get(name: String): ToolCapability? = capabilities[name]

    fun riskLevel(name: String): ToolRiskLevel =
        capabilities[name]?.riskLevel ?: ToolRiskLevel.READ_ONLY

    fun requiresApproval(name: String): Boolean =
        capabilities[name]?.requiresApproval ?: false

    fun all(): List<ToolCapability> = capabilities.values.toList()

    fun names(): Set<String> = capabilities.keys.toSet()

    companion object {
        fun default(): ToolCapabilityRegistry = ToolCapabilityRegistry().apply {
            // Workspace read-only tools
            register(ToolCapability("list_workspace_files", "列出工作区文件", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_current_file", "读取当前文件", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_file", "按 URI 读取文件", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_file_range", "按行范围读取文件", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("search_workspace", "搜索工作区", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("search_symbol", "搜索符号", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_il2cpp_class", "读取 IL2CPP 类信息", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_protocol_record", "读取协议记录", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_so_snapshot", "读取 SO 快照", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_doc", "读取文档", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("read_tool_result", "续读工具结果分页", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("delegate_subagents", "分派子 Agent", ToolRiskLevel.READ_ONLY))

            // GitHub read-only tools
            register(ToolCapability("github_list_repositories", "GitHub 仓库列表", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("github_get_repository", "GitHub 仓库元数据", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("github_list_branches", "GitHub 分支列表", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("github_read_file", "GitHub 读取文件", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("github_list_commits", "GitHub 提交列表", ToolRiskLevel.READ_ONLY))
            register(ToolCapability("github_get_workflow_runs", "GitHub Actions 运行列表", ToolRiskLevel.READ_ONLY))

            // GitHub contribution tools (remote write, require approval)
            register(ToolCapability("github_contribute_fork", "Fork 上游仓库", ToolRiskLevel.REMOTE_WRITE))
            register(ToolCapability("github_contribute_branch", "创建 workbench 分支", ToolRiskLevel.REMOTE_WRITE))
            register(ToolCapability("github_contribute_write", "提交文件到 fork 分支", ToolRiskLevel.REMOTE_WRITE))
            register(ToolCapability("github_contribute_pr", "创建跨 fork PR", ToolRiskLevel.REMOTE_WRITE))

            // Clone repository (local write, requires approval)
            register(ToolCapability("github_clone_repository", "克隆仓库到本地工作区", ToolRiskLevel.LOCAL_WRITE))
        }
    }
}
