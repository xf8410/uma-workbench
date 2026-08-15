package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAiApiAdapterTest {
    @Test fun customTemplatePreservesCompleteMessagesAndEscaping() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol(requestTemplate = """{"id":{{requestIdJson}},"engine":{{modelJson}},"history":{{messagesJson}}}"""))
        val body = adapter.requestBody(
            AiGenerationRequest("id-完整", listOf(AiPromptMessage("user", "完整\n内容\"不裁剪")), "自定义模型"),
            "ignored"
        )
        assertTrue(body.contains("id-完整")); assertTrue(body.contains("自定义模型"))
        assertTrue(body.contains("完整\\n内容\\\"不裁剪"))
    }

    @Test fun arbitraryNestedPathsMapTextModelAndUsage() {
        val protocol = CustomAiApiProtocol(
            streamFormat = AiApiStreamFormat.NDJSON,
            textPath = "result.parts.0.text",
            modelPath = "meta.engine",
            inputTokensPath = "billing.in",
            outputTokensPath = "billing.out",
            totalTokensPath = "billing.all"
        )
        val events = CustomAiApiAdapter(protocol).events("""{"result":{"parts":[{"text":"自定义回复"}]},"meta":{"engine":"vendor-model"},"billing":{"in":8,"out":13,"all":21}}""")
        assertTrue(events.contains(AiStreamEvent.TextDelta("自定义回复")))
        assertTrue(events.contains(AiStreamEvent.Model("vendor-model")))
        assertTrue(events.contains(AiStreamEvent.Usage(AiTokenUsage(8, 13, 21))))
    }

    @Test fun supportsSseAndNdjsonFraming() {
        val sse = CustomAiApiAdapter(CustomAiApiProtocol(streamFormat = AiApiStreamFormat.SSE))
        assertEquals("{\"x\":1}", sse.payload("data: {\"x\":1}")); assertEquals(null, sse.payload("event: message"))
        val ndjson = CustomAiApiAdapter(CustomAiApiProtocol(streamFormat = AiApiStreamFormat.NDJSON))
        assertEquals("{\"x\":1}", ndjson.payload("  {\"x\":1}  "))
    }

    @Test fun configurableDoneMarkerProducesCompletion() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol(doneValue = "END"))
        assertEquals(listOf(AiStreamEvent.Completed), adapter.events("END"))
    }

    @Test fun configurationDoesNotRequireBearerKey() {
        val settings = AiProviderSettings("https://vendor.example/custom-stream", "vendor-model", "{\"X-Custom-Token\":\"secret\"}")
        settings.validate()
        assertTrue(settings.configured)
        assertFalse(settings.headersJson.contains("Authorization"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cleartextEndpointIsRejected() {
        AiProviderSettings("http://vendor.example", "model", "{}").validate()
    }
}
