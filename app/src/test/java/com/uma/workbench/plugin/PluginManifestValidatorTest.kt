package com.uma.workbench.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManifestValidatorTest {
    @Test fun acceptsValidHttpsManifest() {
        val result = PluginManifestValidator.validate(validPluginManifest(), 1)
        assertTrue(result.issues.joinToString { it.code }, result.isValid)
    }

    @Test fun rejectsInsecureTransport() {
        val result = PluginManifestValidator.validate(
            validPluginManifest(PluginTransport.McpHttp("http://plugins.example.com/mcp")), 1
        )
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "insecure_mcp_url" })
    }

    @Test fun rejectsEmbeddedUrlCredentials() {
        val result = PluginManifestValidator.validate(
            validPluginManifest(PluginTransport.McpHttp("https://token@plugins.example.com/mcp")), 1
        )
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "insecure_mcp_url" })
    }

    @Test fun secretsCannotBeCredentialAliases() {
        val result = PluginManifestValidator.validate(
            validPluginManifest(authentication = PluginAuthentication.Bearer("Bearer secret value")), 1
        )
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "invalid_credential_key" })
    }

    @Test fun rejectsIncompatibleVersionAndIncompleteSignature() {
        val value = validPluginManifest().copy(
            compatibility = PluginCompatibility(minimumAppVersion = 3),
            integrity = PluginIntegrity("b".repeat(64), signature = "signature")
        )
        val result = PluginManifestValidator.validate(value, 1)
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "incompatible_app" })
        assertTrue(result.issues.any { it.code == "incomplete_signature" })
    }

    @Test fun unsignedManifestRequiresWarning() {
        val result = PluginManifestValidator.validate(validPluginManifest(integrity = null), 1)
        assertTrue(result.isValid)
        assertTrue(result.issues.any { it.code == "unsigned_manifest" && it.severity == PluginValidationSeverity.WARNING })
    }
}
