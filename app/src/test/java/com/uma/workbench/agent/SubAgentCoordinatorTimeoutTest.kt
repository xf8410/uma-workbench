package com.uma.workbench.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SubAgentCoordinatorCancellationTest {
    private val source = object : ReadonlyAgentToolDataSource {
        override suspend fun listWorkspaceFiles()=""; override suspend fun readCurrentFile()=""
        override suspend fun readFile(uri:String)=""; override suspend fun readFileRange(uri:String,startLine:Int,endLine:Int)=""
        override suspend fun searchWorkspace(query:String,offset:Int,caseSensitive:Boolean)=""; override suspend fun searchSymbol(query:String,offset:Int)=""
        override suspend fun readIl2CppClass(className:String)=""; override suspend fun readProtocolRecord(id:String)=""
        override suspend fun readSoSnapshot(endpoint:String?)=""; override suspend fun readDoc(id:String)=""
    }

    @Test(expected=CancellationException::class)
    fun externalCancellationStillPropagates() = runBlocking {
        val provider=AiStreamingProvider{flow{throw CancellationException("user stop")}}
        SubAgentCoordinator(SubAgentLoopFactory{ReadonlyAgentLoop(provider,ReadonlyAgentToolExecutor(source))}).dispatch(
            AiGenerationRequest("p",emptyList(),"m"),listOf(SubAgentTask("x","x"))
        )
        Unit
    }
}
