package com.uma.workbench.github

fun visibilityWarnings(from: RepositoryVisibility, to: RepositoryVisibility): List<String> {
    if (from == to) return listOf("目标可见性与当前设置相同，不需要修改。")
    return when (to) {
        RepositoryVisibility.PUBLIC -> listOf(
            "代码、Issue、Pull Request、Wiki 和提交历史可能对所有人可见。",
            "历史信息可能已经被复制，改回私有不能撤回已泄露内容。"
        )
        RepositoryVisibility.PRIVATE -> listOf(
            "现有公开链接、Fork、外部协作者和 CI 权限可能受到影响。",
            "依赖此仓库的外部构建可能失败。"
        )
        RepositoryVisibility.INTERNAL -> listOf(
            "仅支持组织内部可见性的 GitHub Enterprise 环境可使用此选项。",
            "组织成员和内部 CI 可能获得访问权限。"
        )
        RepositoryVisibility.UNKNOWN -> listOf("无法确认目标可见性，禁止执行变更。")
    }
}
