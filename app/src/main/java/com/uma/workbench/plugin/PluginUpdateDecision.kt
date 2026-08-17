package com.uma.workbench.plugin

enum class PluginUpdateDecision {
    APPROVED,
    REQUIRES_PERMISSION_REAPPROVAL,
    REJECTED
}

data class PluginUpdateAssessment(
    val decision: PluginUpdateDecision,
    val permissionDiff: PluginPermissionDiff,
    val reason: String? = null
)

object PluginUpdatePolicy {
    fun assess(
        current: RegisteredPlugin,
        next: PluginManifest,
        appVersion: Int
    ): PluginUpdateAssessment {
        val diff = PluginPermissionDiff.between(current.manifest, next)
        val validation = PluginManifestValidator.validate(next, appVersion)
        if (!validation.isValid) {
            return PluginUpdateAssessment(PluginUpdateDecision.REJECTED, diff, "插件清单校验失败")
        }
        if (diff.requiresReapproval) {
            return PluginUpdateAssessment(PluginUpdateDecision.REQUIRES_PERMISSION_REAPPROVAL, diff)
        }
        return PluginUpdateAssessment(PluginUpdateDecision.APPROVED, diff)
    }
}
