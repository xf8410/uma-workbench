package com.uma.workbench.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AiGenerationControllerTest {
    private val request = AiGenerationRequest("request-1", listOf(AiPromptMessage("user", "完整问题")), "model-a")

    @Test fun naturalStreamEndImmediatelyRestoresSendButton() = runTest {
        val controller = AiGenerationController(this) { flow { emit(AiStreamEvent.TextDelta("完整回复")); emit(AiStreamEvent.Usage(AiTokenUsage(11, 22, 33))) } }
        assertTrue(controller.send(request)); advanceUntilIdle()
        val state = controller.state.value
        assertEquals(AiGenerationPhase.COMPLETED, state.phase); assertTrue(state.canSend); assertFalse(state.canInterrupt)
        assertEquals("已完成", state.statusLabel); assertEquals("完整回复", state.completeText); assertEquals(33L, state.usage!!.totalTokens)
    }

    @Test fun explicitCompletionAlsoLeavesGenerating() = runTest {
        val controller = AiGenerationController(this) { flow { emit(AiStreamEvent.Completed) } }
        controller.send(request); advanceUntilIdle()
        assertEquals(AiGenerationPhase.COMPLETED, controller.state.value.phase); assertTrue(controller.state.value.canSend)
    }

    @Test fun interruptionPreservesTextAndReportedTokenUsage() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val controller = AiGenerationController(this) { flow { emit(AiStreamEvent.TextDelta("已经生成的完整部分")); emit(AiStreamEvent.Usage(AiTokenUsage(7, 9, 16))); blocker.await() } }
        controller.send(request); testScheduler.runCurrent(); assertTrue(controller.interrupt()); advanceUntilIdle()
        val state = controller.state.value
        assertEquals(AiGenerationPhase.CANCELLED, state.phase); assertTrue(state.canSend); assertFalse(state.canInterrupt)
        assertEquals("已经生成的完整部分", state.completeText); assertEquals(16L, state.usage!!.totalTokens); assertFalse(state.usage!!.estimated)
    }

    @Test fun interruptionWithoutProviderUsageShowsExplicitEstimate() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val controller = AiGenerationController(this) { flow { emit(AiStreamEvent.TextDelta("12345678")); blocker.await() } }
        controller.send(request); testScheduler.runCurrent(); controller.interrupt(); advanceUntilIdle()
        val state = controller.state.value
        assertEquals(AiGenerationPhase.CANCELLED, state.phase)
        assertNotNull(state.usage); assertTrue(state.usage!!.estimated); assertTrue(state.usageLabel.contains("估算"))
        assertTrue(state.usage!!.totalTokens > 0)
    }

    @Test fun failureNeverLeavesStaleGeneratingState() = runTest {
        val controller = AiGenerationController(this) { flow { throw IllegalStateException("complete provider failure") } }
        controller.send(request); advanceUntilIdle()
        val state = controller.state.value
        assertEquals(AiGenerationPhase.FAILED, state.phase); assertTrue(state.canSend); assertFalse(state.canInterrupt)
        assertTrue(state.error!!.contains("complete provider failure")); assertNotNull(state.usage)
    }

    @Test fun repeatedCumulativeUsageIsNotDoubleCounted() = runTest {
        val controller = AiGenerationController(this) { flow { emit(AiStreamEvent.Usage(AiTokenUsage(10, 2, 12))); emit(AiStreamEvent.Usage(AiTokenUsage(10, 5, 15))) } }
        controller.send(request); advanceUntilIdle()
        assertEquals(AiTokenUsage(10, 5, 15), controller.state.value.usage)
    }

    @Test fun secondSendIsRejectedOnlyWhileRealJobIsActive() = runTest {
        val blocker = CompletableDeferred<Unit>()
        val controller = AiGenerationController(this) { flow { blocker.await() } }
        assertTrue(controller.send(request)); assertFalse(controller.send(request)); controller.interrupt(); advanceUntilIdle()
        assertTrue(controller.send(request.copy(requestId = "request-2"))); controller.interrupt(); advanceUntilIdle()
    }
}
