package com.uma.workbench.github

class GitHubOperationPolicy {
    fun requireConfirmation(operation: String, confirmationId: String?) {
        require(!confirmationId.isNullOrBlank()) { "远程操作必须先完成明确确认：$operation" }
    }

    fun validateVisibilityTarget(target: RepositoryVisibility) {
        require(target != RepositoryVisibility.UNKNOWN) { "未知可见性不能执行远程变更" }
    }

    fun auditMessage(repository: String, operation: String, oldValue: String?, newValue: String?): String =
        "GitHub 设置变更：$repository，操作=$operation，旧值=${oldValue ?: "未知"}，新值=${newValue ?: "未知"}"
}
