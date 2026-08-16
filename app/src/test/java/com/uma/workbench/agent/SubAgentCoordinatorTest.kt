package com.uma.workbench.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentCoordinatorTest {
    private val source = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles() = "files"
        override suspend fun readCurrentFile() = "current"
        override suspend fun readFile(uri: String) = uri
        override suspend fun readFileRange(uri: String, startLine: Int, endLine: Int) = "$uri:$startLine-$endLine"
        override suspend fun searchWorkspace(query: String, offset: Int, caseSensitive: Boolean) = query
        override suspend fun searchSymbol(query: String, offset: Int) = query
        override suspend fun readIl2CppClass(className: String) = className
        override suspend fun readProtocolRecord(id: String) = id
        override suspend fun readSoSnapshot(endpoint: String?) = endpoint ?: "latest"
        override suspend fun readDoc(id: String) = id
    }

    @Test fun givesEachChildIsolatedTaskContextAndPreservesOrder() = runBlocking {
        val requests = mutableListOf<AiGenerationRequest>()
        val provider = AiStreamingProvider { request -> flow {
            synchronized(requests) { requests += request }
            emit(AiStreamEvent.TextDelta("report:${request.messages.last().completeContent}"))
            emit(AiStreamEvent.Completed)
        } }
        val requestNumber = AtomicInteger(0)
        val coordinator = SubAgentCoordinator(
            SubAgentLoopFactory { ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)) },
            requestIdFactory = { "child-${requestNumber.incrementAndGet()}" }
        )
        val parent = AiGenerationRequest(
            "parent",
            listOf(
                AiPromptMessage("system", "shared policy"),
                AiPromptMessage("user", "parent secret history"),
                AiPromptMessage("assistant", "old answer")
            ),
            "model",
            ReadonlyAgentToolSchemas.openAiCompatible
        )

        val outcomes = coordinator.dispatch(parent, listOf(
            SubAgentTask("one", "inspect A"),
            SubAgentTask("two", "inspect B")
        ))

        assertEquals(listOf("one", "two"), outcomes.map { it.taskId })
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("shared policy", request.messages.first().completeContent)
            assertFalse(request.messages.any { it.completeContent.contains("parent secret history") })
            assertFalse(request.messages.any { it.completeContent.contains("old answer") })
            assertTrue(request.messages.last().completeContent.contains("inspect"))
        }
    }

    @Test fun enforcesParallelTaskLimit() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val provider = AiStreamingProvider { flow {
            val now = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, now) }
            try {
                delay(30)
                emit(AiStreamEvent.TextDelta("done"))
                emit(AiStreamEvent.Completed)
            } finally {
                active.decrementAndGet()
            }
        } }
        val coordinator = SubAgentCoordinator(
            SubAgentLoopFactory { ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)) },
            SubAgentLimits(maxTasksPerDispatch = 4, maxParallelTasks = 2)
        )
        coordinator.dispatch(
            AiGenerationRequest("p", listOf(AiPromptMessage("user", "q")), "m"),
            (1..4).map { SubAgentTask("t$it", "task $it") }
        )
        assertTrue(maximum.get() <= 2)
    }

    @Test fun oneChildFailureDoesNotCancelSibling() = runBlocking {
        val provider = AiStreamingProvider { request -> flow {
            if (request.messages.last().completeContent.contains("bad")) error("child failed")
            emit(AiStreamEvent.TextDelta("ok"))
            emit(AiStreamEvent.Completed)
        } }
        val outcomes = SubAgentCoordinator(
            SubAgentLoopFactory { ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)) }
        ).dispatch(
            AiGenerationRequest("p", emptyList(), "m"),
            listOf(SubAgentTask("bad", "bad"), SubAgentTask("good", "good"))
        )
        assertTrue(outcomes[0] is SubAgentOutcome.Failure)
        assertTrue(outcomes[1] is SubAgentOutcome.Success)
    }

    @Test(expected = CancellationException::class)
    fun parentCancellationPropagatesInsteadOfBecomingChildFailure(): Unit = runBlocking {
        val provider = AiStreamingProvider { flow { throw CancellationException("stop") } }
        SubAgentCoordinator(
            SubAgentLoopFactory { ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)) }
        ).dispatch(
            AiGenerationRequest("p", emptyList(), "m"),
            listOf(SubAgentTask("one", "task"))
        )
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDispatchAboveTaskBudget(): Unit = runBlocking {
        val provider = AiStreamingProvider { flow { emit(AiStreamEvent.TextDelta("ok")); emit(AiStreamEvent.Completed) } }
        SubAgentCoordinator(
            SubAgentLoopFactory { ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)) },
            SubAgentLimits(maxTasksPerDispatch = 1, maxParallelTasks = 1)
        ).dispatch(
            AiGenerationRequest("p", emptyList(), "m"),
            listOf(SubAgentTask("one", "1"), SubAgentTask("two", "2"))
        )
        Unit
    }
}
