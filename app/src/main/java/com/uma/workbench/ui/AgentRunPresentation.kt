package com.uma.workbench.ui

import com.uma.workbench.agent.AgentToolOutcome
import com.uma.workbench.agent.ReadonlyAgentRound
import kotlinx.serialization.json.*

data class PersistedSubAgentReportRef(val resultId:String,val totalCharacterCount:Int,val sha256:String)

/** Stores references only; complete reports remain in the durable result store. */
object AgentRunMetadata {
 private val json=Json{ignoreUnknownKeys=true}
 fun toJson(rounds:List<ReadonlyAgentRound>)=buildJsonArray{rounds.forEach{round->add(buildJsonObject{put("round",round.index);put("calls",buildJsonArray{round.toolCalls.zip(round.toolOutcomes).forEach{(call,outcome)->add(buildJsonObject{put("id",call.id);put("name",call.name);when(outcome){is AgentToolOutcome.Success->{put("status","success");put("resultId",outcome.result.resultId);put("totalCharacterCount",outcome.result.totalCharacterCount);put("sha256",outcome.result.sha256)};is AgentToolOutcome.Failure->put("status","failure")}})}})})}}.toString()
 fun subAgentReportRefs(metadata:String?):List<PersistedSubAgentReportRef>{if(metadata.isNullOrBlank())return emptyList();return runCatching{val rounds=json.parseToJsonElement(metadata)as?JsonArray?:return emptyList();rounds.flatMap{roundElement->val round=roundElement as?JsonObject?:return@flatMap emptyList();val calls=round["calls"]as?JsonArray?:return@flatMap emptyList();calls.mapNotNull{element->val call=element as?JsonObject?:return@mapNotNull null;if(call.string("name")!="delegate_subagents"||call.string("status")!="success")return@mapNotNull null;PersistedSubAgentReportRef(call.string("resultId")?.takeIf{it.isNotBlank()}?:return@mapNotNull null,call.primitive("totalCharacterCount")?.intOrNull?.takeIf{it>0}?:return@mapNotNull null,call.string("sha256")?.takeIf{it.isNotBlank()}?:return@mapNotNull null)}}.distinctBy{it.resultId}}.getOrDefault(emptyList())}
 private fun JsonObject.string(n:String)=primitive(n)?.contentOrNull
 private fun JsonObject.primitive(n:String)=get(n)as?JsonPrimitive
}
