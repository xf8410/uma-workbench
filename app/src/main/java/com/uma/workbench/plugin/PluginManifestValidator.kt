package com.uma.workbench.plugin

object PluginManifestValidator {
    fun validate(manifest: PluginManifest, appVersion: Int): PluginValidationResult {
        val issues = mutableListOf<PluginValidationIssue>()
        PluginIdentityValidator.validate(manifest, issues)
        PluginTransportValidator.validate(manifest.transport, issues)
        PluginSecurityValidator.validateAuthentication(manifest.authentication, issues)
        PluginCompatibilityValidator.validate(manifest.compatibility, appVersion, issues)
        PluginSecurityValidator.validateIntegrity(manifest.integrity, issues)
        return PluginValidationResult(issues)
    }
}
