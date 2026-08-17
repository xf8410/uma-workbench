package com.uma.workbench.plugin

internal object PluginIdentityValidator {
    private val id = Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)+$")
    private val version = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
    private val permission = Regex("^[a-z][a-z0-9_.-]*:[a-z*][a-z0-9_.*-]*$")

    fun validate(manifest: PluginManifest, issues: MutableList<PluginValidationIssue>) {
        if (manifest.schemaVersion != PluginManifest.CURRENT_SCHEMA_VERSION) issues.error("unsupported_schema", "不支持插件清单版本 ${manifest.schemaVersion}")
        if (!id.matches(manifest.id)) issues.error("invalid_id", "插件 ID 必须是反向域名式小写标识")
        if (manifest.name.isBlank() || manifest.name.length > 80) issues.error("invalid_name", "插件名称长度必须为 1 到 80")
        if (!version.matches(manifest.version)) issues.error("invalid_version", "插件版本必须使用语义化版本")
        if (!id.matches(manifest.publisher.id)) issues.error("invalid_publisher", "发布者 ID 格式无效")
        manifest.publisher.website?.let { PluginTransportValidator.requireHttps(it, "publisher_website", issues) }
        if (manifest.permissions.distinct().size != manifest.permissions.size) issues.error("duplicate_permission", "插件权限不得重复")
        manifest.permissions.forEach {
            if (!permission.matches(it)) issues.error("invalid_permission", "权限格式无效：$it")
        }
    }
}
