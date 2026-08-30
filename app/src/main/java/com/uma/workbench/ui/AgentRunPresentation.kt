package com.uma.workbench.ui

import com.uma.workbench.agent.AgentToolOutcome
import com.uma.workbench.agent.OutputVerification
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

 /** 模型输出验证结果 → JSON（附加到消息 metadata 数组末尾；rounds 解析对非 round 对象天然跳过）。 */
 fun verificationJson(v:OutputVerification)=buildJsonObject{
  put("verification",buildJsonObject{
   put("status",v.status.name);put("totalToolCalls",v.totalToolCalls);put("successfulToolCalls",v.successfulToolCalls)
   put("failedToolCalls",v.failedToolCalls);put("evidenceSnippetsReferenced",v.evidenceSnippetsReferenced)
   put("toolNamesUsed",buildJsonArray{v.toolNamesUsed.forEach{add(it)}})
   put("warnings",buildJsonArray{v.warnings.forEach{add(it)}})
  })
 }.toString()

 /** 把验证对象追加到现有 metadata JSON 数组末尾；解析失败时从零开始重建数组。 */
 fun appendVerification(metadata:String?,v:OutputVerification):String{
  val root=metadata?.let{runCatching{json.parseToJsonElement(it)as?JsonArray}.getOrNull()}?:JsonArray(emptyList())
  val element=json.parseToJsonElement(verificationJson(v))
  return JsonArray(root+element).toString()
 }

 /** 从消息 metadata 还原验证结果；无验证记录时返回 null（旧消息）。 */
 fun verificationOf(metadata:String?):OutputVerification?{
  if(metadata.isNullOrBlank())return null
  return runCatching{
   val root=json.parseToJsonElement(metadata)as?JsonArray?:return null
   val v=root.firstNotNullOfOrNull{element->val o=element as?JsonObject?:return@firstNotNullOfOrNull null;o["verification"]as?JsonObject}?:return null
   fun objString(n:String)=(v[n]as?JsonPrimitive)?.contentOrNull
   fun objInt(n:String)=(v[n]as?JsonPrimitive)?.contentOrNull?.toIntOrNull()
   val status=objString("status")?:return null
   OutputVerification(
    status=OutputVerification.VerificationStatus.valueOf(status),
    totalToolCalls=objInt("totalToolCalls")?:0,
    successfulToolCalls=objInt("successfulToolCalls")?:0,
    failedToolCalls=objInt("failedToolCalls")?:0,
    evidenceSnippetsReferenced=objInt("evidenceSnippetsReferenced")?:0,
    toolNamesUsed=(v["toolNamesUsed"]as?JsonArray)?.mapNotNull{it as?JsonPrimitive}?.map{it.content}?.toSet()?:emptySet(),
    warnings=(v["warnings"]as?JsonArray)?.mapNotNull{it as?JsonPrimitive}?.map{it.content}?:emptyList()
   )
  }.getOrNull()
 }
}
