package com.uma.workbench.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProviderTest {

    @Test
    fun noModelProvider_returnsDeterministicResponse() = runBlocking {
        val provider = NoModelProvider()
        val request = ModelGenerationRequest(
            messages = listOf(
                AiPromptMessage("system", "你是一个审计助手"),
                AiPromptMessage("user", "分析这个APK")
            )
        )
        val result = provider.generate(request)
        assertTrue("确定性模式标记应存在", result.text.contains("确定性模式"))
        assertEquals("no-model", result.model)
        assertNotNull(result.usage)
        assertEquals(0L, result.usage!!.totalTokens)
    }

    @Test
    fun noModelProvider_isAlwaysAvailable() {
        val provider = NoModelProvider()
        assertTrue(provider.isAvailable)
        assertEquals("no-model", provider.id)
    }

    @Test
    fun noModelProvider_handlesEmptyMessages() = runBlocking {
        val provider = NoModelProvider()
        val result = provider.generate(ModelGenerationRequest(messages = emptyList()))
        assertTrue(result.text.contains("确定性模式"))
        assertEquals("no-model", result.model)
    }

    @Test
    fun streamingModelProvider_collectsEventsIntoResult() = runBlocking {
        val fakeProvider = AiStreamingProvider { _ ->
            flow {
                emit(AiStreamEvent.TextDelta("Hello "))
                emit(AiStreamEvent.TextDelta("World"))
                emit(AiStreamEvent.Model("test-model"))
                emit(AiStreamEvent.Usage(AiTokenUsage(10, 20, 30)))
                emit(AiStreamEvent.Completed)
            }
        }
        val bridge = StreamingModelProvider(fakeProvider)
        val request = ModelGenerationRequest(
            messages = listOf(AiPromptMessage("user", "test"))
        )
        val result = bridge.generate(request)
        assertEquals("Hello World", result.text)
        assertEquals("test-model", result.model)
        assertEquals(30L, result.usage!!.totalTokens)
    }

    @Test
    fun streamingModelProvider_isAvailable() {
        val fakeProvider = AiStreamingProvider { flow {} }
        val bridge = StreamingModelProvider(fakeProvider)
        assertTrue(bridge.isAvailable)
    }
}
