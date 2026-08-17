package com.uma.workbench.plugin

internal object PluginCompatibilityValidator {
    fun validate(
        compatibility: PluginCompatibility,
        appVersion: Int,
        issues: MutableList<PluginValidationIssue>
    ) {
        if (compatibility.minimumAppVersion < 1) {
            issues.error("invalid_min_app_version", "最低 App 版本必须大于零")
        }
        if (compatibility.maximumAppVersion != null &&
            compatibility.maximumAppVersion < compatibility.minimumAppVersion
        ) issues.error("invalid_version_range", "最高 App 版本不得低于最低版本")
        if (appVersion < compatibility.minimumAppVersion ||
            compatibility.maximumAppVersion?.let { appVersion > it } == true
        ) issues.error("incompatible_app", "插件与当前 App 版本不兼容")
    }
}
