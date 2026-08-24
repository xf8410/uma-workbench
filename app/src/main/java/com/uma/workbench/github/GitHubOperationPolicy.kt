package com.uma.workbench.github

class GitHubOperationPolicy(
    private val confirmationStore: GitHubConfirmationStore? = null
) {
    fun requireConfirmation(operation: GitHubRemoteOperation, description: String, confirmationId: String?) {
        if (confirmationStore == null) {
            require(!confirmationId.isNullOrBlank()) { "远程操作必须先完成明确确认：$description" }
            return
        }
        val result = confirmationStore.consume(confirmationId ?: "", operation)
        require(result == GitHubConfirmationStore.ConsumeResult.OK) {
            "授权令牌无效（${result.name}），操作被拒绝：$description。请在 UI 重新发放授权令牌"
        }
    }

    fun validateVisibilityTarget(target: RepositoryVisibility) {
        require(target != RepositoryVisibility.UNKNOWN) { "未知可见性不能执行远程变更" }
    }

    fun auditMessage(repository: String, operation: String, oldValue: String?, newValue: String?): String =
        "GitHub 设置变更：$repository，操作=$operation，旧值=${oldValue ?: "未知"}，新值=${newValue ?: "未知"}"
}
