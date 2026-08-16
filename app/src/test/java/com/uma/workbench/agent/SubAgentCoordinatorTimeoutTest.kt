package com.uma.workbench.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentCoordinatorTimeoutTest {
    private val source = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles()=""; override suspend fun readCurrentFile()=""
        override suspend fun readFile(uri:String)=""; override suspend fun readFileRange(uri:String,startLine:Int,endLine:Int)=""
        override suspend fun searchWorkspace(query:String,offset:Int,caseSensitive:Boolean)=""; override suspend fun searchSymbol(query:String,offset:Int)=""
        override suspend fun readIl2CppClass(className:String)=""; override suspend fun readProtocolRecord(id:String)=""
        override suspend fun readSoSnapshot(endpoint:String?)=""; override suspend fun readDoc(id:String)=""
    }

    @Test fun timedOutChildDoesNotCancelSibling() = runBlocking {
        val provider = AiStreamingProvider { request -> flow {
            if (request.messages.last().completeContent.contains("slow")) delay(5_000)
            emit(AiStreamEvent.TextDelta("ok")); emit(AiStreamEvent.Completed)
        } }
        val coordinator = SubAgentCoordinator(
            SubAgentLoopFactory { ReadonlyAgentLoop(provider, ReadonlyAgentToolExecutor(source)) },
            SubAgentLimits(maxTasksPerDispatch=2,maxParallelTasks=2,timeoutMillisPerTask=1_000)
        )
        val outcomes = coordinator.dispatch(AiGenerationRequest("p",emptyList(),"m"),listOf(SubAgentTask("slow","slow"),SubAgentTask("fast","fast")))
        assertTrue(outcomes[0] is SubAgentOutcome.Failure)
        assertTrue((outcomes[0] as SubAgentOutcome.Failure).failure.completeError.contains("运行时限"))
        assertTrue(outcomes[1] is SubAgentOutcome.Success)
    }

    @Test(expected=CancellationException::class)
    fun externalCancellationStillPropagates() = runBlocking {
        val provider=AiStreamingProvider{flow{throw CancellationException("user stop")}}
        SubAgentCoordinator(SubAgentLoopFactory{ReadonlyAgentLoop(provider,ReadonlyAgentToolExecutor(source))},SubAgentLimits(timeoutMillisPerTask=5_000)).dispatch(AiGenerationRequest("p",emptyList(),"m"),listOf(SubAgentTask("x","x")))
        Unit
    }
}
