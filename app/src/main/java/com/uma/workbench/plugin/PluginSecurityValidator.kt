package com.uma.workbench.plugin

internal object PluginSecurityValidator {
    private val sha256 = Regex("^[0-9a-fA-F]{64}$")
    private val credentialKey = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun validateAuthentication(auth: PluginAuthentication, issues: MutableList<PluginValidationIssue>) {
        when (auth) {
            PluginAuthentication.None -> Unit
            is PluginAuthentication.Bearer -> validateKey(auth.credentialKey, issues)
            is PluginAuthentication.ApiKey -> {
                validateKey(auth.credentialKey, issues)
                if (auth.headerName.isBlank() || auth.headerName.any { it <= ' ' || it == ':' }) issues.error("invalid_auth_header", "API Key 请求头名称无效")
            }
        }
    }

    fun validateIntegrity(integrity: PluginIntegrity?, issues: MutableList<PluginValidationIssue>) {
        if (integrity == null) {
            issues.warning("unsigned_manifest", "插件清单没有完整性信息，安装时必须额外确认")
            return
        }
        if (!sha256.matches(integrity.manifestSha256)) issues.error("invalid_sha256", "清单 SHA-256 格式无效")
        if ((integrity.signature == null) != (integrity.signingKeyId == null)) issues.error("incomplete_signature", "签名和签名密钥 ID 必须同时提供")
    }

    private fun validateKey(key: String, issues: MutableList<PluginValidationIssue>) {
        if (!credentialKey.matches(key)) issues.error("invalid_credential_key", "凭据只能通过安全别名引用")
    }
}
