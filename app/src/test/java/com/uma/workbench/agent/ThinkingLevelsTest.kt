package com.uma.workbench.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingLevelsTest {
    private val request = AiGenerationRequest("r", listOf(AiPromptMessage("user", "问")), "model-x")

    @Test fun defaultTemplateInjectsReasoningEffortWhenLevelSet() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol(), thinkingLevel = "high")
        val body = adapter.requestBody(request, "model-x")
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
    }

    @Test fun defaultTemplateOmitsReasoningEffortWhenNull() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol(), thinkingLevel = null)
        val body = adapter.requestBody(request, "model-x")
        assertFalse(body.contains("reasoning_effort"))
    }

    @Test fun invalidLevelIsIgnored() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol(), thinkingLevel = "ultra")
        val body = adapter.requestBody(request, "model-x")
        assertFalse(body.contains("reasoning_effort"))
    }

    @Test fun customTemplateWithoutPlaceholderStaysBackwardCompatible() {
        val adapter = CustomAiApiAdapter(
            CustomAiApiProtocol(requestTemplate = """{"model":{{modelJson}},"messages":{{messagesJson}}}}"""),
            thinkingLevel = "low"
        )
        val body = adapter.requestBody(request, "model-x")
        assertFalse(body.contains("reasoning_effort"))
        assertTrue(body.contains("model-x"))
    }

    @Test fun customTemplateCanUseThinkingPropertyPlaceholder() {
        val adapter = CustomAiApiAdapter(
            CustomAiApiProtocol(requestTemplate = """{"model":{{modelJson}},"messages":{{messagesJson}}{{thinkingProperty}}}}"""),
            thinkingLevel = "medium"
        )
        val body = adapter.requestBody(request, "model-x")
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
    }

    @Test fun displayAndRequestValueRoundTrip() {
        assertTrue(ThinkingLevels.toRequestValue("关闭") == null)
        assertTrue(ThinkingLevels.toRequestValue("high") == "high")
        assertTrue(ThinkingLevels.toDisplayValue(null) == "关闭")
        assertTrue(ThinkingLevels.toDisplayValue("low") == "low")
        // 非法存档值显示为关闭，不会进请求
        assertTrue(ThinkingLevels.toRequestValue(ThinkingLevels.toDisplayValue("garbage")!!) == null)
    }
}
