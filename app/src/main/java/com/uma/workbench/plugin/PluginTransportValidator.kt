package com.uma.workbench.plugin

import java.net.URI

internal object PluginTransportValidator {
    private val repository = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun validate(transport: PluginTransport, issues: MutableList<PluginValidationIssue>) {
        when (transport) {
            is PluginTransport.McpHttp -> requireHttps(transport.url, "mcp_url", issues)
            is PluginTransport.OpenApi -> {
                requireHttps(transport.documentUrl, "openapi_document_url", issues)
                transport.baseUrl?.let { requireHttps(it, "openapi_base_url", issues) }
            }
            is PluginTransport.GitHubWorkflow -> {
                if (!repository.matches(transport.repository)) {
                    issues.error("invalid_repository", "GitHub 仓库必须使用 owner/name 格式")
                }
                if (transport.workflow.isBlank() || transport.workflow.contains("..")) {
                    issues.error("invalid_workflow", "Workflow 标识无效")
                }
            }
        }
    }

    fun requireHttps(url: String, field: String, issues: MutableList<PluginValidationIssue>) {
        val valid = runCatching {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
        if (!valid) issues.error(
            "insecure_$field",
            "$field 必须是无内嵌凭据的 HTTPS 地址"
        )
    }
}

internal fun MutableList<PluginValidationIssue>.error(code: String, message: String) {
    add(PluginValidationIssue(code, message))
}

internal fun MutableList<PluginValidationIssue>.warning(code: String, message: String) {
    add(PluginValidationIssue(code, message, PluginValidationSeverity.WARNING))
}
