package com.uma.workbench.agent

import org.junit.Assert.*
import org.junit.Test

class AiProviderCatalogTest {
    private val first = AiApiCredential(id = "key-1", label = "主密钥", secret = "sk-1234567890abcd")
    private val second = AiApiCredential(id = "key-2", label = "备用", secret = "secret-2", enabled = false)
    private val provider = AiProviderProfile(id = "provider-1", name = "自定义 GPT", baseUrl = "https://example.test/v1", credentials = listOf(first, second), selectedCredentialId = first.id)

    @Test fun providerOwnsMultipleKeysAndUsesSelectedEnabledKey() {
        assertEquals(first, provider.activeCredential)
        assertTrue(provider.configured)
        assertEquals("sk-1••••••••abcd", first.masked)
        assertFalse(first.masked.contains("1234567890"))
    }

    @Test fun modelsStayScopedByProviderAndDefaultUsesCompositeIdentity() {
        val other = provider.copy(id = "provider-2", name = "另一个", credentials = listOf(first.copy(id = "key-3")), models = listOf("same-model"))
        var catalog = AiProviderCatalog(listOf(provider, other))
        catalog = catalog.withModels(provider.id, listOf("same-model", "new-model", "same-model"))
        catalog = catalog.select(AiModelSelection(provider.id, "same-model"))
        assertEquals(AiModelSelection("provider-1", "same-model"), catalog.defaultModel)
        assertEquals(listOf("new-model", "same-model"), catalog.providers.first().models)
        assertEquals(listOf("same-model"), catalog.providers.last().models)
    }

    @Test fun removingProviderAlsoClearsItsDefaultModel() {
        val catalog = AiProviderCatalog(listOf(provider.copy(models = listOf("m"))), AiModelSelection(provider.id, "m"))
        assertNull(catalog.remove(provider.id).defaultModel)
    }

    @Test fun modelDiscoveryAcceptsCommonCompleteResponseShapes() {
        val discovery = AiModelDiscovery()
        assertEquals(listOf("a", "b"), discovery.extract(kotlinx.serialization.json.Json.parseToJsonElement("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}""")))
        assertEquals(listOf("x", "y"), discovery.extract(kotlinx.serialization.json.Json.parseToJsonElement("""{"models":["y",{"name":"x"}]}""")))
    }
}
