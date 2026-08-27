package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class SubAgentReportSummary(val taskId:String,val status:String,val answer:String?,val error:String?,val requestId:String?,val model:String?,val totalTokens:Long?,val evidenceCount:Int,val completeEvidenceCount:Int,val roundsCount:Int?=null,val toolCallCount:Int?=null,val elapsedMillis:Long?=null)
object SubAgentReportPresentation{
 private val json=Json{ignoreUnknownKeys=true}
 fun parse(content:String):List<SubAgentReportSummary>?=runCatching{
  val root=json.parseToJsonElement(content)as?JsonObject?:return null
  val type=root.string("type")
  if(type!="sub_agent_reports"&&type!="sub_agent_report_manifest")return null
  val reports=root["reports"]as?JsonArray?:return null
  reports.map{element->val r=element as?JsonObject?:error("report 必须是 object");val evidence=r["evidence"]as?JsonArray
   SubAgentReportSummary(
    taskId=r.string("taskId")?.takeIf{it.isNotBlank()}?:error("缺少 taskId"),status=r.string("status")?:"unknown",
    answer=r.string("answer")?:r.string("preview")?.takeIf{r.string("status")=="success"},
    error=r.string("error")?:r.string("preview")?.takeIf{r.string("status")=="failure"},requestId=r.string("requestId"),model=r.string("model"),
    totalTokens=(r["usage"]as?JsonObject)?.primitive("totalTokens")?.longOrNull?:r.primitive("totalTokens")?.longOrNull,
    evidenceCount=evidence?.size?:r.primitive("evidenceCount")?.longOrNull?.toInt()?:0,
    completeEvidenceCount=evidence?.count{((it as?JsonObject)?.primitive("complete")?.booleanOrNull==true)}?:r.primitive("completeEvidenceCount")?.longOrNull?.toInt()?:0,
    roundsCount=r.primitive("roundsCount")?.longOrNull?.toInt(),
    toolCallCount=r.primitive("toolCallCount")?.longOrNull?.toInt(),
    elapsedMillis=r.primitive("elapsedMillis")?.longOrNull)
  }
 }.getOrNull()
 private fun JsonObject.string(n:String)=primitive(n)?.contentOrNull
 private fun JsonObject.primitive(n:String)=get(n)as?JsonPrimitive
}
