package com.uma.workbench.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentDelegationHandlerTest {
    private val source=object:ReadonlyAgentToolDataSource{override suspend fun listWorkspaceFiles()="files";override suspend fun readCurrentFile()="evidence";override suspend fun readFile(uri:String)=uri;override suspend fun readFileRange(uri:String,startLine:Int,endLine:Int)="range";override suspend fun searchWorkspace(query:String,offset:Int,caseSensitive:Boolean)=query;override suspend fun searchSymbol(query:String,offset:Int)=query;override suspend fun readIl2CppClass(className:String)=className;override suspend fun readProtocolRecord(id:String)=id;override suspend fun readSoSnapshot(endpoint:String?)=endpoint?:"latest";override suspend fun readDoc(id:String)=id}

 @Test fun persistsCompleteReportButReturnsCompactManifestToParent()=runBlocking{
  var rootRound=0;val rootProvider=AiStreamingProvider{flow{rootRound++;if(rootRound==1)emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0,"delegate-1","delegate_subagents","{\"tasks\":[{\"id\":\"audit\",\"instruction\":\"inspect current file\"}]}")))else emit(AiStreamEvent.TextDelta("root final"));emit(AiStreamEvent.Completed)}}
  var childRound=0;var childTools:kotlinx.serialization.json.JsonArray?=null;val childProvider=AiStreamingProvider{request->flow{childTools=request.tools;childRound++;if(childRound==1)emit(AiStreamEvent.ToolCallDelta(AiToolCallDelta(0,"read-1","read_current_file","{}")))else emit(AiStreamEvent.TextDelta("child report"));emit(AiStreamEvent.Completed)}}
  val store=InMemoryAgentToolResultStore();val coordinator=SubAgentCoordinator(SubAgentLoopFactory{ReadonlyAgentLoop(childProvider,ReadonlyAgentToolExecutor(source,resultStore=store))});val root=ReadonlyAgentLoop(rootProvider,ReadonlyAgentToolExecutor(source,resultStore=store),specialToolHandler=SubAgentDelegationHandler(coordinator))
  val result=root.run(AiGenerationRequest("root",listOf(AiPromptMessage("user","audit")),"m",ReadonlyAgentToolSchemas.openAiCompatible));assertEquals("root final",result.completeAnswer)
  val delegation=(result.rounds.first().toolOutcomes.single()as AgentToolOutcome.Success).result
  val manifest=Json.parseToJsonElement(delegation.content).jsonObject;assertEquals("sub_agent_report_manifest",manifest["type"]!!.jsonPrimitive.content);assertFalse(delegation.content.contains("\"answer\":\"child report\""));assertTrue(delegation.content.contains("\"preview\":\"child report\""))
  val complete=store.read(delegation.resultId,0,delegation.totalCharacterCount);val report=Json.parseToJsonElement(complete.content).jsonObject["reports"]!!.jsonArray.single().jsonObject;assertEquals("child report",report["answer"]!!.jsonPrimitive.content);assertEquals("read_current_file",report["evidence"]!!.jsonArray.single().jsonObject["tool"]!!.jsonPrimitive.content);assertTrue(complete.complete)
  assertEquals(ReadonlyAgentToolSchemas.childInvestigation,childTools);assertFalse(childTools.toString().contains("delegate_subagents"))
 }

 @Test fun compactReportParsesTaskCardMetrics(){
  val json="""{"type":"sub_agent_reports","tasks":[{"taskId":"t1","status":"success","requestId":"r1","totalTokens":1200,"roundsCount":3,"toolCallCount":7,"elapsedMillis":4500,"evidenceCount":5,"completeEvidenceCount":4}]}"""
  val reports=SubAgentReportPresentation.parse(json)
  org.junit.Assert.assertNotNull(reports);val r=reports!!.single()
  org.junit.Assert.assertEquals(3,r.roundsCount);org.junit.Assert.assertEquals(7,r.toolCallCount);org.junit.Assert.assertEquals(4500L,r.elapsedMillis)
  // 旧格式（无新字段）解析为 null，不崩
  val legacy="""{"type":"sub_agent_reports","tasks":[{"taskId":"t2","status":"success","requestId":"r2","totalTokens":10,"evidenceCount":1,"completeEvidenceCount":1}]}"""
  val lr=SubAgentReportPresentation.parse(legacy)!!.single()
  org.junit.Assert.assertNull(lr.roundsCount);org.junit.Assert.assertNull(lr.toolCallCount);org.junit.Assert.assertNull(lr.elapsedMillis)
 }

 @Test fun invalidUnknownTaskPropertyBecomesVisibleToolFailure()=runBlocking{
  val coordinator=SubAgentCoordinator(SubAgentLoopFactory{ReadonlyAgentLoop(AiStreamingProvider{flow{emit(AiStreamEvent.TextDelta("unused"));emit(AiStreamEvent.Completed)}},ReadonlyAgentToolExecutor(source))});val executor=ReadonlyAgentToolExecutor(source);val call=AiToolCall(0,"d","delegate_subagents","{\"tasks\":[{\"id\":\"x\",\"instruction\":\"y\",\"unknown\":1}]}");val outcome=executor.executeSpecial(call){SubAgentDelegationHandler(coordinator).execute(AiGenerationRequest("p",emptyList(),"m"),call)};assertTrue((outcome as AgentToolOutcome.Failure).failure.completeError.contains("未知参数"))
 }
}
