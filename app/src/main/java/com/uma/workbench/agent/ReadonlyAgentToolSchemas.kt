package com.uma.workbench.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ReadonlyAgentToolSchemas {
    /** Children stay workspace-local and cannot recursively delegate or consume GitHub API quota. */
    val childReadOnly: JsonArray = buildJsonArray { addWorkspaceReadOnlyTools() }

    /** 调查型子 Agent：工作区只读 + 克隆（克隆受审批门约束）。 */
    val childInvestigation: JsonArray = buildJsonArray {
        addWorkspaceReadOnlyTools()
        addGitHubCloneTools()
    }

    /** GitHub tools are initially visible only to the root Agent. */
    val openAiCompatible: JsonArray = buildJsonArray {
        addWorkspaceReadOnlyTools()
        addWorkspaceWriteTools()
        addGitHubReadOnlyTools()
        addGitHubContributionTools()
        addGitHubCloneTools()
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "delegate_subagents")
                put("description", "将彼此独立的只读证据调查任务分派给受预算约束的子 Agent；仅在任务确实可并行时使用")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        put("tasks", buildJsonObject {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", 4)
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", false)
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject { put("type", "string") })
                                    put("instruction", buildJsonObject { put("type", "string") })
                                    put("evidenceRequirements", buildJsonObject { put("type", "string") })
                                })
                                put("required", buildJsonArray {
                                    add(JsonPrimitive("id")); add(JsonPrimitive("instruction"))
                                })
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("tasks")) })
                })
            })
        })
    }

    private fun JsonArrayBuilder.addWorkspaceReadOnlyTools() {
        function("list_workspace_files", "列出当前工作区允许读取的文件")
        function("read_current_file", "读取当前活动文件；较大结果返回 resultId 和下一 offset，可用 read_tool_result 继续完整读取")
        function("read_file", "按工作区 URI 读取文件", strings = listOf("uri"), required = listOf("uri"))
        function("read_file_range", "按工作区 URI 和行号读取范围", strings = listOf("uri"), integers = listOf("startLine", "endLine"), required = listOf("uri", "startLine", "endLine"))
        function("search_workspace", "在当前工作区进行分页字面量搜索", strings = listOf("query"), integers = listOf("offset"), booleans = listOf("caseSensitive"), required = listOf("query"))
        function("search_symbol", "在当前工作区进行区分大小写的符号字面量搜索", strings = listOf("query"), integers = listOf("offset"), required = listOf("query"))
        function("read_il2cpp_class", "通过本地 hlpatch 读取完整 IL2CPP 类字段和方法，包括嵌套类名", strings = listOf("className"), required = listOf("className"))
        function("read_protocol_record", "按 ID 读取协议记录", strings = listOf("id"), required = listOf("id"))
        function("read_so_snapshot", "读取本地 hlpatch GET 相对端点；不限制合法动态端点、查询参数或嵌套类名", strings = listOf("endpoint"))
        function("read_doc", "按 ID 读取当前工作区 Doc", strings = listOf("id"), required = listOf("id"))
        function("read_tool_result", "按 resultId、offset、limit 精确续读先前工具的完整本地结果，直到 complete=true", strings = listOf("resultId"), integers = listOf("offset", "limit"), required = listOf("resultId", "offset"))
    }

    /**
     * 工作区本地写工具：仅主 Agent 可见，子 Agent 拿不到（childReadOnly/childInvestigation 不含）。
     * 双重门控：AgentMode.ACT 才允许 LOCAL_WRITE，且每次执行都过 ToolApprovalGate 用户审批。
     * uri 必须属于当前工作区（活动文件/最近文件/已导入来源），克隆仓库文件写 file:// URI。
     */
    private fun JsonArrayBuilder.addWorkspaceWriteTools() {
        function(
            "write_workspace_file",
            "把完整新内容写回当前工作区中已授权的文件：uri 必须来自 list_workspace_files/read_file 返回的工作区文件；content 是整个文件的新内容（UTF-8 不超过 48000 字节，超限会被拒绝）；仅「执行」模式可用且每次都需要用户在审批弹窗确认。改法：先 read_file 读全文，在原文基础上修改后整体写回",
            strings = listOf("uri", "content"),
            required = listOf("uri", "content")
        )
    }

    private fun JsonArrayBuilder.addGitHubReadOnlyTools() {
        function(
            "github_list_repositories",
            "列出当前登录 GitHub 账号可访问的仓库；page 从 1 开始",
            positiveIntegers = listOf("page")
        )
        function(
            "github_get_repository",
            "读取一个 GitHub 仓库的元数据",
            strings = listOf("owner", "name"),
            required = listOf("owner", "name")
        )
        function(
            "github_list_branches",
            "列出一个 GitHub 仓库的分支，最多 100 条",
            strings = listOf("owner", "name"),
            required = listOf("owner", "name")
        )
        function(
            "github_read_file",
            "读取 GitHub 仓库中的文件或目录；path 可为空字符串表示根目录",
            strings = listOf("owner", "name", "ref", "path"),
            required = listOf("owner", "name", "ref", "path")
        )
        function(
            "github_list_commits",
            "分页列出 GitHub 仓库指定 ref 的提交，单次最多 50 条",
            strings = listOf("owner", "name", "ref"),
            positiveIntegers = listOf("page"),
            required = listOf("owner", "name", "ref")
        )
        function(
            "github_get_workflow_runs",
            "分页读取 GitHub Actions 工作流运行，单次最多 20 条",
            strings = listOf("owner", "name"),
            positiveIntegers = listOf("page"),
            required = listOf("owner", "name")
        )
    }

    private fun JsonArrayBuilder.addGitHubContributionTools() {
        function(
            "github_contribute_fork",
            "GitHub 贡献流第1步：fork 上游仓库到当前账号。需要 confirmationId（UI 确认流程发放）；返回的 progress JSON 传给下一步工具",
            strings = listOf("owner", "repo", "confirmationId"),
            required = listOf("owner", "repo", "confirmationId")
        )
        function(
            "github_contribute_branch",
            "GitHub 贡献流第2步：在 fork 上创建 workbench/* 分支。progress 传上一步返回的 JSON；分支名必须以 workbench/ 开头",
            strings = listOf("progress", "branch", "confirmationId"),
            required = listOf("progress", "branch", "confirmationId")
        )
        function(
            "github_contribute_write",
            "GitHub 贡献流第3步：把一个文件的内容提交到 fork 分支。progress 传上一步返回的 JSON；可多次调用累积提交",
            strings = listOf("progress", "path", "content", "commitMessage", "confirmationId"),
            required = listOf("progress", "path", "content", "commitMessage", "confirmationId")
        )
        function(
            "github_contribute_pr",
            "GitHub 贡献流第4步：从 fork 分支向上游仓库发起跨 fork PR。progress 传上一步返回的 JSON；要求至少已提交一个文件",
            strings = listOf("progress", "title", "body", "confirmationId"),
            booleans = listOf("draft"),
            required = listOf("progress", "title", "body", "confirmationId")
        )
    }

    /** 克隆工具：本地写性质，主 Agent 与调查型子 Agent 都可用。 */
    private fun JsonArrayBuilder.addGitHubCloneTools() {
        function(
            "github_clone_repository",
            "把 GitHub 仓库完整克隆到本地工作区（下载 tarball 并解包）。克隆后用 list_workspace_files 列出全部文件，read_file/search_workspace 直接读取。ref 留空用默认分支",
            strings = listOf("owner", "repo", "ref"),
            required = listOf("owner", "repo")
        )
    }

    private fun JsonArrayBuilder.function(
        name: String,
        description: String,
        strings: List<String> = emptyList(),
        integers: List<String> = emptyList(),
        positiveIntegers: List<String> = emptyList(),
        booleans: List<String> = emptyList(),
        required: List<String> = emptyList()
    ) {
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name); put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        strings.forEach { put(it, buildJsonObject { put("type", "string") }) }
                        integers.forEach { property -> put(property, buildJsonObject {
                            put("type", "integer"); put("minimum", if (property == "limit") 1 else 0)
                        }) }
                        positiveIntegers.forEach { property -> put(property, buildJsonObject {
                            put("type", "integer"); put("minimum", 1)
                        }) }
                        booleans.forEach { put(it, buildJsonObject { put("type", "boolean") }) }
                    })
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                })
            })
        })
    }
}
