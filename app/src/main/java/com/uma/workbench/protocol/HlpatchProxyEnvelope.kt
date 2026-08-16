package com.uma.workbench.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class HlpatchProxyRequestEnvelope(
    val endpoint:String,val sid:String?,val viewerId:Long?,val headers:Map<String,String>,val body:String,val bodyEncrypted:Boolean,val timestamp:Long,
    val headerEntries:List<ProtocolHeader> = ProtocolHeaders.fromMap(headers)
)
data class HlpatchProxyResponseEnvelope(
    val httpStatus:Int,val protocolCode:Int?,val headers:Map<String,String>,val body:String,val bodyDecrypted:String?,val latencyMs:Long,val success:Boolean,val error:String?,
    val headerEntries:List<ProtocolHeader> = ProtocolHeaders.fromMap(headers)
){fun toGameResponse(timestamp:Long)=GameResponse(httpStatus,ProtocolStatusCode.fromCode(protocolCode?:httpStatus),headers,body,bodyDecrypted,latencyMs,timestamp,success,headerEntries)}

object HlpatchProxyEnvelopeCodec{
 private val json=Json
 fun from(request:GameRequest)=HlpatchProxyRequestEnvelope(request.rawEndpoint,request.sid,request.viewerId,request.headers,request.body,request.bodyEncrypted,request.timestamp,request.headerEntries)
 fun encodeRequest(request:GameRequest)=encodeRequest(from(request)).toString()
 fun encodeRequest(value:HlpatchProxyRequestEnvelope)=JsonObject(linkedMapOf("endpoint" to JsonPrimitive(value.endpoint),"sid" to nullable(value.sid),"viewer_id" to nullable(value.viewerId),"headers" to encodeHeaders(value.headerEntries),"body" to JsonPrimitive(value.body),"body_encrypted" to JsonPrimitive(value.bodyEncrypted),"timestamp" to JsonPrimitive(value.timestamp)))
 fun decodeRequest(encoded:String)=decodeRequest(json.parseToJsonElement(encoded).jsonObject)
 fun decodeRequest(value:JsonObject):HlpatchProxyRequestEnvelope{val entries=decodeHeaders(value["headers"]?:error("missing headers"));return HlpatchProxyRequestEnvelope(value.requiredString("endpoint"),value.optionalString("sid"),value.optionalLong("viewer_id"),ProtocolHeaders.compatibilityMap(entries),value.requiredString("body"),value.requiredBoolean("body_encrypted"),value.requiredLong("timestamp"),entries)}
 fun encodeResponse(value:HlpatchProxyResponseEnvelope)=JsonObject(linkedMapOf("http_status" to JsonPrimitive(value.httpStatus),"protocol_code" to nullable(value.protocolCode),"headers" to encodeHeaders(value.headerEntries),"body" to JsonPrimitive(value.body),"body_decrypted" to nullable(value.bodyDecrypted),"latency_ms" to JsonPrimitive(value.latencyMs),"success" to JsonPrimitive(value.success),"error" to nullable(value.error))).toString()
 fun decodeResponse(encoded:String):HlpatchProxyResponseEnvelope{val value=json.parseToJsonElement(encoded).jsonObject;val entries=decodeHeaders(value["headers"]?:error("missing headers"));return HlpatchProxyResponseEnvelope(value.requiredInt("http_status"),value.optionalInt("protocol_code"),ProtocolHeaders.compatibilityMap(entries),value.requiredString("body"),value.optionalString("body_decrypted"),value.requiredLong("latency_ms"),value.requiredBoolean("success"),value.optionalString("error"),entries)}
 private fun encodeHeaders(headers:List<ProtocolHeader>)=JsonArray(headers.map{JsonObject(linkedMapOf("name" to JsonPrimitive(it.name),"value" to JsonPrimitive(it.value)))})
 private fun decodeHeaders(value:JsonElement):List<ProtocolHeader>=when(value){is JsonArray->value.mapIndexed{i,e->val o=e as? JsonObject?:error("header[$i] must be object");ProtocolHeader(o.requiredString("name"),o.requiredString("value"))};is JsonObject->value.entries.map{ProtocolHeader(it.key,it.value.jsonPrimitive.content)};else->error("headers must be array or legacy object")}
 private fun nullable(v:String?):JsonElement=v?.let(::JsonPrimitive)?:JsonNull;private fun nullable(v:Long?):JsonElement=v?.let(::JsonPrimitive)?:JsonNull;private fun nullable(v:Int?):JsonElement=v?.let(::JsonPrimitive)?:JsonNull
 private fun JsonObject.requiredString(n:String)=get(n)?.jsonPrimitive?.contentOrNull?:error("missing string: $n");private fun JsonObject.requiredLong(n:String)=get(n)?.jsonPrimitive?.longOrNull?:error("missing long: $n");private fun JsonObject.requiredInt(n:String)=get(n)?.jsonPrimitive?.intOrNull?:error("missing int: $n");private fun JsonObject.requiredBoolean(n:String)=get(n)?.jsonPrimitive?.booleanOrNull?:error("missing boolean: $n");private fun JsonObject.optionalString(n:String)=get(n).unlessNull()?.jsonPrimitive?.contentOrNull;private fun JsonObject.optionalLong(n:String)=get(n).unlessNull()?.jsonPrimitive?.longOrNull;private fun JsonObject.optionalInt(n:String)=get(n).unlessNull()?.jsonPrimitive?.intOrNull;private fun JsonElement?.unlessNull()=if(this==null||this is JsonNull)null else this
}
