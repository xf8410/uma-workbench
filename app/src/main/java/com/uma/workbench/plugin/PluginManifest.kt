package com.uma.workbench.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Data-only plugin contract. Plugins describe remote tools; they do not load APK or DEX code. */
@Serializable
data class PluginManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val publisher: PluginPublisher,
    val transport: PluginTransport,
    val permissions: List<String> = emptyList(),
    val authentication: PluginAuthentication = PluginAuthentication.None,
    val compatibility: PluginCompatibility = PluginCompatibility(),
    val integrity: PluginIntegrity? = null
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class PluginPublisher(
    val id: String,
    val name: String,
    val website: String? = null
)

@Serializable
sealed interface PluginTransport {
    @Serializable
    @SerialName("mcp_http")
    data class McpHttp(val url: String) : PluginTransport

    @Serializable
    @SerialName("openapi")
    data class OpenApi(val documentUrl: String, val baseUrl: String? = null) : PluginTransport

    @Serializable
    @SerialName("github_workflow")
    data class GitHubWorkflow(val repository: String, val workflow: String) : PluginTransport
}

@Serializable
sealed interface PluginAuthentication {
    @Serializable
    @SerialName("none")
    data object None : PluginAuthentication

    /** credentialKey is an opaque alias. A manifest must never contain the secret value. */
    @Serializable
    @SerialName("bearer")
    data class Bearer(val credentialKey: String) : PluginAuthentication

    @Serializable
    @SerialName("api_key")
    data class ApiKey(
        val credentialKey: String,
        val headerName: String = "Authorization"
    ) : PluginAuthentication
}

@Serializable
data class PluginCompatibility(
    val minimumAppVersion: Int = 1,
    val maximumAppVersion: Int? = null
)

@Serializable
data class PluginIntegrity(
    val manifestSha256: String,
    val signature: String? = null,
    val signingKeyId: String? = null
)
