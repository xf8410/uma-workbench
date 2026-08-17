package com.uma.workbench.plugin

enum class PluginValidationSeverity { ERROR, WARNING }

data class PluginValidationIssue(
    val code: String,
    val message: String,
    val severity: PluginValidationSeverity = PluginValidationSeverity.ERROR
)

data class PluginValidationResult(val issues: List<PluginValidationIssue>) {
    val isValid: Boolean get() = issues.none { it.severity == PluginValidationSeverity.ERROR }
}
