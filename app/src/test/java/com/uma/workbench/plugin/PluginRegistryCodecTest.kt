package com.uma.workbench.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginRegistryCodecTest {
    private val codec = PluginRegistryCodec()

    @Test fun roundTripsRegisteredPlugin() {
        val original = PluginLifecycle.install(validPluginManifest(), 100L).copy(
            state = PluginLifecycleState.ENABLED,
            updatedAt = 200L,
            lastError = "previous error"
        )
        val decoded = codec.decode(codec.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun persistedManifestDoesNotContainCredentialSecret() {
        val plugin = PluginLifecycle.install(
            validPluginManifest(authentication = PluginAuthentication.Bearer("github_token")),
            100L
        )
        val encoded = codec.encode(plugin)
        assertEquals(false, encoded.manifestJson.contains("secret-value"))
        assertEquals(true, encoded.manifestJson.contains("github_token"))
    }
}
