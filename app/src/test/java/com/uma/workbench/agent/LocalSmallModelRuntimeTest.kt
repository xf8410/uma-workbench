package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地小模型运行时插件层测试。 */
class LocalSmallModelRuntimeTest {

    @Test
    fun registryContainsLoopbackBridge() {
        assertNotNull(LocalSmallModelRuntimes.byId("loopback-bridge"))
    }

    @Test
    fun registryLookupUnknownIdReturnsNull() {
        assertEquals(null, LocalSmallModelRuntimes.byId("no-such-runtime"))
    }

    @Test
    fun loopbackDefaultEndpointTargetsLocalhost() {
        val ep = LoopbackBridgeRuntime.defaultEndpoint()
        assertTrue(ep.baseUrl.startsWith("http://127.0.0.1"))
    }

    @Test
    fun loopbackEndpointPassesPrivateNetworkValidation() {
        val ep = LoopbackBridgeRuntime.defaultEndpoint().copy(model = "qwen2.5-1.5b-instruct")
        ep.validate()
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", ep.chatUrl())
    }

    @Test
    fun loopbackEndpointModelsUrl() {
        val ep = LoopbackBridgeRuntime.defaultEndpoint().copy(model = "m")
        assertEquals("http://127.0.0.1:8080/v1/models", ep.modelsUrl())
    }

    @Test
    fun everyRegisteredRuntimeHasUniqueIdAndLabel() {
        val ids = LocalSmallModelRuntimes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        LocalSmallModelRuntimes.all.forEach { runtime ->
            assertTrue(runtime.label.isNotBlank())
            assertTrue(runtime.defaultEndpoint().baseUrl.isNotBlank())
        }
    }
}
