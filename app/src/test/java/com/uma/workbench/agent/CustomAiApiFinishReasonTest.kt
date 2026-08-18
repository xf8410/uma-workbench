package com.uma.workbench.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAiApiFinishReasonTest {
    @Test fun stopFinishReasonCompletesAfterTextAndUsage() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol())
        val events = adapter.events("""{"model":"test-model","choices":[{"delta":{"content":"最后一段"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""")
        assertTrue(events.contains(AiStreamEvent.TextDelta("最后一段")))
        assertTrue(events.contains(AiStreamEvent.Usage(AiTokenUsage(7, 3, 10))))
        assertEquals(AiStreamEvent.Completed, events.last())
    }

    @Test fun lengthFinishReasonAlsoCompletesWithoutDoneMarker() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol())
        val events = adapter.events("""{"choices":[{"delta":{},"finish_reason":"length"}]}""")
        assertEquals(listOf(AiStreamEvent.Completed), events)
    }

    @Test fun emptyFinishReasonDoesNotComplete() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol())
        val events = adapter.events("""{"choices":[{"delta":{"content":"继续"},"finish_reason":null}]}""")
        assertEquals(listOf(AiStreamEvent.TextDelta("继续")), events)
    }

    @Test fun customFinishReasonPathIsSupported() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol(finishReasonPath = "meta.end"))
        val events = adapter.events("""{"meta":{"end":"stop"}}""")
        assertEquals(listOf(AiStreamEvent.Completed), events)
    }
}
